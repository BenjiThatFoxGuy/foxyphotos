import Foundation
import UserNotifications

/// Tells the user a background pass finished uploads and is waiting to delete.
///
/// A background pass cannot present iOS's deletion prompt. iOS surfaces a
/// photo-library change request as a notification that pulls the user into the
/// app, but the app must not depend on that: it posts its own so a pending
/// deletion is never silent. Tapping it foregrounds the app, which finishes the
/// deletion through the normal system prompt.
enum DeletionNotifier {
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
    }

    static func notify(entries: Int) {
        guard entries > 0 else { return }
        let content = UNMutableNotificationContent()
        content.title = "FoxyPhotos"
        content.body = "\(entries) confirmed \(entries == 1 ? "original is" : "originals are") ready to delete. Tap to review."
        content.sound = .default
        // A fixed identifier replaces an earlier pending-deletion notification
        // instead of stacking them.
        let request = UNNotificationRequest(identifier: "foxyphotos-pending-deletion",
                                            content: content,
                                            trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
