import SwiftUI

@main
struct FoxyPhotosApp: App {
    init() {
        // BGTaskScheduler handlers must be registered before launch completes,
        // otherwise the app cannot submit background work later.
        IOSBackgroundJobs.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
