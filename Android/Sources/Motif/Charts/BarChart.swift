import SwiftUI

/// One bar: its value and the label under it, if it gets one.
struct Bar: Identifiable, Equatable {
    let id: String
    let value: Double
    var label: String?
    /// Periods that haven't happened yet are drawn as an empty slot, not a zero.
    var isFuture = false
}

/// Vertical bars in the primary colour, with an optional dashed average and a value scale.
///
/// Drawn from plain shapes rather than a charting library, so it's the same on Android and in
/// previews, and every colour is a Material role.
struct BarChart: View {
    let bars: [Bar]
    var average: Double?
    var height: CGFloat = 160
    /// Turns a value into the scale's labels ("2h", "40").
    var scaleLabel: (Double) -> String = { "\(Int($0))" }

    private var top: Double {
        let highest = max(bars.map(\.value).max() ?? 0, average ?? 0)
        return Self.niceCeiling(highest)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                plot
                scale
            }
            labels
        }
        .replacedAccessibility()
    }

    private var plot: some View {
        ZStack(alignment: .bottomLeading) {
            gridLines
            HStack(alignment: .bottom, spacing: bars.count > 20 ? 2 : 4) {
                ForEach(bars) { bar in
                    barView(bar)
                }
            }
            .frame(height: height, alignment: .bottom)
            if let average, top > 0, average > 0 {
                AverageLine()
                    .offset(y: -height * average / top)
            }
        }
        .frame(height: height)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func barView(_ bar: Bar) -> some View {
        let fraction = top > 0 ? bar.value / top : 0
        if bar.isFuture || bar.value <= 0 {
            // A sliver where the bar would be, so the rhythm of the axis reads.
            Color.clear
                .frame(maxWidth: .infinity)
                .frame(height: 3)
                .materialBackground(.surfaceContainerHighest, cornerRadius: 1.5)
        } else {
            Color.clear
                .frame(maxWidth: .infinity)
                .frame(height: max(4, height * fraction))
                .materialBackground(.primary, cornerRadius: bars.count > 20 ? 2 : 4)
        }
    }

    private var gridLines: some View {
        VStack(spacing: 0) {
            ForEach(0..<2, id: \.self) { _ in
                Color.clear.frame(height: 1).materialBackground(.outlineVariant)
                Spacer(minLength: 0)
            }
            Color.clear.frame(height: 1).materialBackground(.outlineVariant)
        }
        .frame(height: height)
    }

    private var scale: some View {
        VStack(alignment: .leading, spacing: 0) {
            scaleText(top)
            Spacer(minLength: 0)
            scaleText(top / 2)
            Spacer(minLength: 0)
            scaleText(0)
        }
        .frame(width: 40, height: height, alignment: .leading)
    }

    private func scaleText(_ value: Double) -> some View {
        Text(scaleLabel(value))
            .materialType(.labelSmall)
            .materialForeground(.onSurfaceVariant)
            .lineLimit(1)
    }

    private var labels: some View {
        HStack(spacing: bars.count > 20 ? 2 : 4) {
            ForEach(bars) { bar in
                Text(bar.label ?? " ")
                    .materialType(.labelSmall)
                    .materialForeground(.onSurfaceVariant)
                    .lineLimit(1)
                    .fixedSize()
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Color.clear.frame(width: 48, height: 1)
        }
    }

    /// Rounds the highest value up to a number worth putting on an axis, whose half is one too:
    /// 1, 2, 4, 6 or 8 times a power of ten, so the midline reads 30m, 1h, 2h, 3h or 4h rather
    /// than 1h 15m. Close enough above the highest bar that the bars fill the plot.
    static func niceCeiling(_ value: Double) -> Double {
        guard value > 0 else { return 1 }
        let magnitude = pow(10, floor(log10(value)))
        for step in [1.0, 2.0, 4.0, 6.0, 8.0, 10.0] where value <= step * magnitude {
            return step * magnitude
        }
        return 10 * magnitude
    }
}

/// The average, as a line in the outline colour across the plot.
///
/// A plain rule rather than dashes: a row of fixed-width dashes wider than the plot makes
/// Compose measure the whole chart wider than its card, pushing the last bars out of sight.
struct AverageLine: View {
    var body: some View {
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: 1.5)
            .materialBackground(.outline)
            .accessibilityHidden(true)
    }
}
