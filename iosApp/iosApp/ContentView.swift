import SwiftUI
// FoxyPhotosPlatform is the Kotlin/Native framework Gradle builds from
// core/platform. Its file facade class is `FoxyPhotosPlatformKt`.
import FoxyPhotosPlatform

struct ContentView: View {
    // Rendered by the shared Kotlin core, not by Swift: this line is the proof
    // that core/platform actually links and runs on iOS.
    private let coreStamp = FoxyPhotosPlatformKt.formatCurrentTimeIso()

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "photo.stack")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
            Text("FoxyPhotos")
                .font(.largeTitle.bold())
            Text("Shared Kotlin core is reachable from iOS.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(coreStamp)
                .font(.footnote.monospaced())
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
