import SwiftUI
import PasalaKit

struct SectionLabel: View {
    let text: LocalizedStringKey
    init(_ t: LocalizedStringKey) { text = t }
    var body: some View {
        Text(text).font(.system(size: 11, weight: .heavy, design: .rounded)).tracking(1.3).foregroundStyle(Theme.dim)
    }
}

struct Backdrop: View {
    @State private var t = false
    var body: some View {
        ZStack {
            Theme.ink
            Circle().fill(Theme.violet.opacity(0.35)).frame(width: 380).blur(radius: 120).offset(x: t ? 140 : 90, y: t ? -330 : -280)
            Circle().fill(Theme.orange.opacity(0.2)).frame(width: 320).blur(radius: 120).offset(x: t ? -150 : -110, y: t ? 330 : 380)
        }
        .ignoresSafeArea()
        .onAppear { withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) { t = true } }
    }
}

extension View {
    func card(highlight: Bool = false) -> some View {
        background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.surface))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(highlight ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.faint), lineWidth: 1))
    }
}

