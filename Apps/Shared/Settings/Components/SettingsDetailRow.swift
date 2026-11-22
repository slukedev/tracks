import SwiftUI

/// A name with a line under it and a control after, the control centered on the whole
/// text block. `Toggle` and `LabeledContent` hang a two-line label from its first line,
/// which leaves the control riding high beside the title.
struct SettingsDetailRow<Trailing: View>: View {
    var title: Text
    var detail: Text
    var systemImage: String?
    var tint: Color
    var trailing: Trailing

    init(
        title: Text,
        detail: Text,
        systemImage: String? = nil,
        tint: Color = .accentColor,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.title = title
        self.detail = detail
        self.systemImage = systemImage
        self.tint = tint
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: SettingsSpacing.row) {
            if let systemImage {
                SettingsIconTile(systemImage: systemImage, color: tint)
            }
            VStack(alignment: .leading, spacing: Design.Spacing.xxSmall) {
                title
                detail
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Separators start under the words, as they do after a Label's icon.
            .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
            trailing
        }
        .padding(.vertical, Design.Spacing.xxSmall)
    }
}

extension SettingsDetailRow where Trailing == EmptyView {
    init(title: Text, detail: Text, systemImage: String? = nil, tint: Color = .accentColor) {
        self.init(title: title, detail: detail, systemImage: systemImage, tint: tint) { EmptyView() }
    }
}

/// An on/off setting. On the Mac, a detail row when there's a line worth saying under it;
/// on iPhone, a plain switch, with anything to explain in its group's footer as iOS Settings
/// does it.
struct SettingsSwitch: View {
    var title: LocalizedStringKey
    var detail: Text?
    @Binding var isOn: Bool

    init(_ title: LocalizedStringKey, detail: Text? = nil, isOn: Binding<Bool>) {
        self.title = title
        self.detail = detail
        self._isOn = isOn
    }

    var body: some View {
        #if os(macOS)
        if let detail {
            SettingsDetailRow(title: Text(title), detail: detail) {
                Toggle(title, isOn: $isOn)
                    .toggleStyle(.switch)
                    .labelsHidden()
            }
        } else {
            Toggle(title, isOn: $isOn)
                .toggleStyle(.switch)
        }
        #else
        Toggle(title, isOn: $isOn)
        #endif
    }
}
