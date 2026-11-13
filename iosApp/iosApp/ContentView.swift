import Photos
import SwiftUI
import UIKit

private struct PhotoAlbum: Identifiable, Sendable {
    static let cameraRollId = "foxyphotos:camera-roll"

    let id: String
    let title: String
    let imageCount: Int
    let videoCount: Int
}

/** PhotoKit queries may be slow on large libraries; never call this on the main actor. */
private func scanAlbums(progress: @escaping (Int, Int) -> Void) -> [PhotoAlbum] {
    let imageOptions = PHFetchOptions()
    imageOptions.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
    let videoOptions = PHFetchOptions()
    videoOptions.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.video.rawValue)

    let collections = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
    let total = 2 + 2 * collections.count // One PhotoKit fetch per media type and album.
    var completed = 0
    progress(completed, total)
    let cameraImages = PHAsset.fetchAssets(with: .image, options: nil).count
    completed += 1
    progress(completed, total)
    let cameraVideos = PHAsset.fetchAssets(with: .video, options: nil).count
    completed += 1
    progress(completed, total)
    let cameraRoll = PhotoAlbum(id: PhotoAlbum.cameraRollId,
                                title: "Camera Roll",
                                imageCount: cameraImages,
                                videoCount: cameraVideos)
    var userAlbums: [PhotoAlbum] = []
    collections.enumerateObjects { collection, _, _ in
        let imageCount = PHAsset.fetchAssets(in: collection, options: imageOptions).count
        completed += 1
        progress(completed, total)
        let videoCount = PHAsset.fetchAssets(in: collection, options: videoOptions).count
        completed += 1
        progress(completed, total)
        if imageCount + videoCount > 0 {
            userAlbums.append(PhotoAlbum(id: collection.localIdentifier,
                                         title: collection.localizedTitle ?? "Album",
                                         imageCount: imageCount, videoCount: videoCount))
        }
    }
    return [cameraRoll] + userAlbums.sorted {
        $0.title.localizedStandardCompare($1.title) == .orderedAscending
    }
}

@MainActor
private final class PhotoSelection: ObservableObject {
    @Published var access: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published var albums: [PhotoAlbum] = []
    @Published var selected: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "selected-albums") ?? [])
    @Published private(set) var isRefreshing = false
    @Published private(set) var scanProgress = 0.0

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
        guard !isRefreshing else { return }
        isRefreshing = true
        scanProgress = 0
        Task { [weak self] in
            let loaded = await Task.detached(priority: .userInitiated) { [weak self] in
                scanAlbums { completed, total in
                    // Bound UI updates even for libraries with thousands of albums.
                    guard completed == 0 || completed == total || completed % max(1, total / 100) == 0 else { return }
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.isRefreshing else { return }
                        self.scanProgress = Double(completed) / Double(total)
                    }
                }
            }.value
            guard let self else { return }
            defer { self.isRefreshing = false }
            // Permission can change while PhotoKit is scanning.
            self.access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            guard self.hasAccess else { self.albums = []; return }
            self.albums = loaded
            if UserDefaults.standard.object(forKey: "selected-albums") == nil {
                self.selected = [PhotoAlbum.cameraRollId]
                UserDefaults.standard.set([PhotoAlbum.cameraRollId], forKey: "selected-albums")
            }
        }
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
                        if selection.access == .denied || selection.access == .restricted {
                            Text("Photos access is off. Enable it in Settings to choose albums.")
                                .foregroundStyle(.secondary)
                            if let settings = URL(string: UIApplication.openSettingsURLString) {
                                Link("Open Settings", destination: settings)
                            }
                        } else {
                            Button("Choose Photos access") { selection.requestAccess() }
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
                        if selection.isRefreshing {
                            ProgressView("Loading albums… \(Int(selection.scanProgress * 100))%", value: selection.scanProgress, total: 1)
                                .progressViewStyle(.linear)
                        }
                        if selection.albums.isEmpty && !selection.isRefreshing {
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
                    Button {
                        selection.refresh()
                    } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                    .disabled(selection.isRefreshing)
                }
            }
            .onAppear { selection.refresh() }
        }
    }
}
