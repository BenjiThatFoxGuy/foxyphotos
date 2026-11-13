import Photos
import SwiftUI

private struct PhotoAlbum: Identifiable {
    let id: String
    let title: String
    let imageCount: Int
    let videoCount: Int
}

@MainActor
private final class PhotoSelection: ObservableObject {
    @Published var access: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published var albums: [PhotoAlbum] = []
    @Published var selected: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "selected-albums") ?? [])

    var hasAccess: Bool { access == .authorized || access == .limited }

    func requestAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] status in
            Task { @MainActor [weak self] in
                self?.access = status
                self?.refresh()
            }
        }
    }

    func refresh() {
        access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard hasAccess else { albums = []; return }
        var result: [PhotoAlbum] = []
        for type in [PHAssetCollectionType.smartAlbum, .album] {
            let collections = PHAssetCollection.fetchAssetCollections(with: type, subtype: .any, options: nil)
            collections.enumerateObjects { collection, _, _ in
                let assets = PHAsset.fetchAssets(in: collection, options: nil)
                var imageCount = 0
                var videoCount = 0
                assets.enumerateObjects { asset, _, _ in
                    switch asset.mediaType {
                    case .image: imageCount += 1
                    case .video: videoCount += 1
                    default: break
                    }
                }
                if imageCount + videoCount > 0 {
                    result.append(PhotoAlbum(id: collection.localIdentifier,
                                             title: collection.localizedTitle ?? "Album",
                                             imageCount: imageCount, videoCount: videoCount))
                }
            }
        }
        albums = result.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
        UserDefaults.standard.set(Array(selected).sorted(), forKey: "selected-albums")
    }
}

struct ContentView: View {
    @StateObject private var selection = PhotoSelection()

    var body: some View {
        NavigationStack {
            List {
                if !selection.hasAccess {
                    Section {
                        Label("Allow Photos access to choose albums for backup.", systemImage: "photo.on.rectangle")
                        Button("Choose Photos access") { selection.requestAccess() }
                        if selection.access == .denied || selection.access == .restricted {
                            Text("Photos access is off. Enable it in Settings to choose albums.")
                                .foregroundStyle(.secondary)
                        }
                    }
                } else {
                    if selection.access == .limited {
                        Section {
                            Text("Only photos you allowed are visible. Change the selection in iOS Settings to include more.")
                                .foregroundStyle(.secondary)
                        }
                    }
                    Section("Albums to back up") {
                        if selection.albums.isEmpty {
                            Text("No accessible albums found.").foregroundStyle(.secondary)
                        }
                        ForEach(selection.albums) { album in
                            Button { selection.toggle(album.id) } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(album.title).foregroundStyle(.primary)
                                        Text("\(album.imageCount) photos · \(album.videoCount) videos")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if selection.selected.contains(album.id) {
                                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint)
                                    }
                                }
                            }
                            .accessibilityLabel("\(album.title), \(selection.selected.contains(album.id) ? "selected" : "not selected")")
                        }
                    }
                    Section {
                        Button("Back up selected albums") {}
                            .disabled(true)
                        Text("Uploads are not available yet. Your album selection is saved on this device.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("FoxyPhotos")
            .toolbar {
                if selection.hasAccess {
                    Button("Refresh", systemImage: "arrow.clockwise") { selection.refresh() }
                }
            }
            .onAppear { selection.refresh() }
        }
    }
}
