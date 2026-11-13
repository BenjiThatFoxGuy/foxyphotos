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
    @State private var importingCredential = false
    @State private var showingPrivatePicker = false
    @State private var pickerReport: String?
    @State private var credentialInput = ""
    @State private var credentialConfigured = GoogleCredentialStore.isConfigured
    @State private var credentialError: String?
    @State private var deleteConfirmedOriginals = false
    @State private var isBackingUp = false
    @State private var backupProgress = 0.0
    @State private var backupStatus: String?
    @State private var backupTask: Task<Void, Never>?
    @State private var backupSession: IOSBackupSession?

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
                    Section("Backup provider") {
                        Button(credentialConfigured ? "Replace Google credential" : "Paste Google credential") {
                            credentialError = nil
                            importingCredential = true
                        }
                        Text(credentialConfigured
                             ? "Credential stored on this device."
                             : "Paste your Google Photos credential string before backing up.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Section("Backup") {
                        Toggle("Delete confirmed originals after backup", isOn: $deleteConfirmedOriginals)
                            .disabled(isBackingUp)
                        Text("iOS will confirm deletion of simple originals only, after Google confirms and the receipt is saved. Live Photos and edited assets are kept.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Button("Back up selected albums") { startBackup() }
                            .disabled(isBackingUp || selection.isRefreshing || !credentialConfigured || selection.selected.isEmpty)
                        if isBackingUp {
                            ProgressView("Backing up… \(Int(backupProgress * 100))%", value: backupProgress, total: 1)
                                .progressViewStyle(.linear)
                            Button("Cancel backup") { backupSession?.cancel(); backupTask?.cancel() }
                        }
                        if let backupStatus {
                            Text(backupStatus).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                Section("Private picker test") {
                    Button("Inspect a few photos with iOS picker") { showingPrivatePicker = true }
                        .disabled(isBackingUp)
                    Text("Debug only. This does not upload or delete anything. The report shows which filenames and albums iOS actually exposes.")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let pickerReport {
                        Button("Copy picker report") { UIPasteboard.general.string = pickerReport }
                        Text(pickerReport).font(.footnote).textSelection(.enabled)
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
            .onAppear {
                selection.refresh()
                credentialConfigured = GoogleCredentialStore.isConfigured
            }
            .sheet(isPresented: $showingPrivatePicker) {
                PrivatePhotoPicker { report in
                    showingPrivatePicker = false
                    if !report.isEmpty { pickerReport = report }
                }
            }
            .sheet(isPresented: $importingCredential, onDismiss: {
                credentialInput = ""
                credentialError = nil
            }) {
                NavigationStack {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Paste the Google Photos auth string used on Android.")
                            .font(.footnote).foregroundStyle(.secondary)
                        TextEditor(text: $credentialInput)
                            .frame(height: 120)
                            .scrollContentBackground(.hidden)
                            .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .privacySensitive()
                            .accessibilityLabel("Google Photos credential")
                        if let credentialError {
                            Text(credentialError).font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding()
                    .navigationTitle("Google credential")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { credentialInput = ""; importingCredential = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Save") {
                                do {
                                    try GoogleCredentialStore.save(credentialInput)
                                    credentialConfigured = GoogleCredentialStore.isConfigured
                                    credentialError = nil
                                    credentialInput = ""
                                    importingCredential = false
                                } catch GoogleCredentialStore.ImportError.invalidCredential {
                                    credentialError = "Invalid Google credential string. Check the pasted value."
                                } catch {
                                    credentialError = "Could not save credential. The previous one was kept."
                                }
                            }
                            .disabled(credentialInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                }
                .presentationDetents([.medium])
            }
        }
    }

    @MainActor private func startBackup() {
        guard !isBackingUp, credentialConfigured, selection.hasAccess, !selection.selected.isEmpty else { return }
        let session = IOSBackupSession()
        backupSession = session
        isBackingUp = true
        backupProgress = 0
        backupStatus = "Preparing selected originals…"
        let chosen = selection.selected
        let albumNames = Dictionary(uniqueKeysWithValues: selection.albums
            .filter { chosen.contains($0.id) }.map { ($0.id, $0.title) })
        let shouldDelete = deleteConfirmedOriginals
        backupTask = Task {
            do {
                let result = try await session.run(selected: chosen, albumNames: albumNames,
                                                   deleteConfirmed: shouldDelete) { completed, total, failed in
                    await MainActor.run {
                        backupProgress = total == 0 ? 1 : Double(completed) / Double(total)
                        backupStatus = "Checked \(completed) of \(total) · \(failed) failed"
                    }
                }
                backupProgress = 1
                backupStatus = "\(result.confirmed) confirmed, \(result.skipped) already backed up, \(result.failed) failed."
                if result.albumRequests > 0 {
                    backupStatus! += " \(result.albumRequests) album placements acknowledged, not remotely verified."
                }
                if result.albumUnresolved > 0 {
                    let issues = result.albumIssues.sorted { $0.key < $1.key }
                        .map { "\($0.key): \($0.value)" }.joined(separator: "; ")
                    backupStatus! += " \(result.albumUnresolved) album placements unresolved (\(issues)). No uncertain writes were replayed."
                }
                if result.deletionRequested > 0 {
                    backupStatus! += result.deletionSucceeded
                        ? " \(result.deletionRequested) originals deleted with iOS approval."
                        : " Original deletion wasn't completed; confirmed backups remain saved."
                }
                if result.deletionIneligible > 0 {
                    backupStatus! += " \(result.deletionIneligible) complex assets kept on device."
                }
            } catch is CancellationError {
                backupStatus = "Backup cancelled. Confirmed receipts are saved; no deletion was started."
            } catch {
                backupStatus = "Backup could not start. No original was deleted."
            }
            isBackingUp = false
            backupSession = nil
            backupTask = nil
        }
    }
}
