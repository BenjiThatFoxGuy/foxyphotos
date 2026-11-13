import ActivityKit
import SwiftUI
import WidgetKit

/// Lock Screen / Dynamic Island surface for a running backup or album index.
struct AlbumIndexLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AlbumIndexAttributes.self) { context in
            AlbumIndexLiveActivityView(name: context.attributes.name, state: context.state)
                .padding(.horizontal, 4)
                .activityBackgroundTint(.black.opacity(0.85))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("FoxyPhotos", systemImage: "photo.on.rectangle.angled")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text("\(context.state.completed)/\(context.state.total)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ProgressView(value: context.state.progress)
                        .tint(.accentColor)
                }
            } compactLeading: {
                Image(systemName: "photo.on.rectangle.angled")
            } compactTrailing: {
                Text("\(context.state.completed)")
                    .font(.caption2.monospacedDigit())
            } minimal: {
                Image(systemName: "photo.on.rectangle.angled")
            }
        }
    }
}

private extension AlbumIndexAttributes.ContentState {
    var progress: Double {
        total > 0 ? min(1, max(0, Double(completed) / Double(total))) : 0
    }
}

struct AlbumIndexLiveActivityView: View {
    let name: String
    let state: AlbumIndexAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(name).font(.headline)
                Spacer(minLength: 8)
                Text("\(state.completed)/\(state.total)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            ProgressView(value: state.progress)
            Text(state.phase)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
