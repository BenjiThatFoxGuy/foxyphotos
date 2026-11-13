import Foundation
import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Diagnostic only. The system picker does not grant PhotoKit album access by itself.
struct PrivatePhotoPicker: UIViewControllerRepresentable {
    let onReport: (String) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = 20
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onReport: onReport) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onReport: (String) -> Void
        init(onReport: @escaping (String) -> Void) { self.onReport = onReport }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard !results.isEmpty else { onReport(""); return }
            let group = DispatchGroup()
            let lock = NSLock()
            var entries: [(Int, String)] = []
            for (index, result) in results.enumerated() {
                let provider = result.itemProvider
                let id = result.assetIdentifier
                var details = ["Item \(index + 1)",
                               "Picker asset ID: \(id ?? "not supplied")",
                               "Provider suggested name: \(provider.suggestedName ?? "not supplied")",
                               "Provider types: \(provider.registeredTypeIdentifiers.joined(separator: ", "))"]
                if let id, let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject {
                    details += ["PhotoKit access: yes", "Media type: \(asset.mediaType == .image ? "image" : asset.mediaType == .video ? "video" : "other")",
                                "Dimensions: \(asset.pixelWidth) × \(asset.pixelHeight)",
                                "Duration: \(asset.duration) seconds", "Subtype flags: \(asset.mediaSubtypes.rawValue)",
                                "Source type flags: \(asset.sourceType.rawValue)",
                                "Created: \(asset.creationDate?.description ?? "unknown")",
                                "Modified: \(asset.modificationDate?.description ?? "unknown")"]
                    if let location = asset.location {
                        details.append("Location: \(location.coordinate.latitude), \(location.coordinate.longitude)")
                    }
                    let resources = PHAssetResource.assetResources(for: asset)
                    for (resourceIndex, resource) in resources.enumerated() {
                        details.append("Resource \(resourceIndex + 1): original filename=\(resource.originalFilename), type=\(resource.type.rawValue), UTI=\(resource.uniformTypeIdentifier)")
                    }
                    if resources.isEmpty { details.append("PhotoKit resources: none") }
                    let albums = PHAssetCollection.fetchAssetCollectionsContaining(asset, with: .album, options: nil)
                    if albums.count == 0 { details.append("User albums: none visible (Camera Roll may still contain it)") }
                    albums.enumerateObjects { album, _, _ in
                        details.append("User album: \(album.localizedTitle ?? "untitled") [\(album.localIdentifier)]")
                    }
                } else {
                    details.append("PhotoKit access: no; source album, original filename and deletion eligibility cannot be verified")
                }
                // File representation is temporary. Inspect it inside the callback; never upload or retain it.
                guard let type = provider.registeredTypeIdentifiers.first(where: {
                    guard let contentType = UTType($0) else { return false }
                    return contentType.conforms(to: .image) || contentType.conforms(to: .movie)
                }) else {
                    details.append("File representation: no image/video type registered")
                    entries.append((index, details.joined(separator: "\n")))
                    continue
                }
                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                    var item = details
                    if let url, error == nil {
                        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
                        item.append("Picker representation: type=\(type), extension=\(url.pathExtension), bytes=\(size.map { String($0) } ?? "unknown")")
                        item.append("Picker temporary filename: \(url.lastPathComponent) (not the original filename)")
                    } else { item.append("Picker file representation: unavailable (\(error?.localizedDescription ?? "no file URL"))") }
                    lock.lock()
                    entries.append((index, item.joined(separator: "\n")))
                    lock.unlock()
                    group.leave()
                }
            }
            group.notify(queue: .main) { [onReport] in
                onReport("Private picker diagnostic only. Nothing was uploaded or deleted.\n\n" +
                         entries.sorted { $0.0 < $1.0 }.map(\.1).joined(separator: "\n\n"))
            }
        }
    }
}
