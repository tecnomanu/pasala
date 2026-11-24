import SwiftUI
import AppKit
import QuickLookThumbnailing
import UniformTypeIdentifiers
import PasalaCore

enum Theme {
    static let ink = Color(red: 0.055, green: 0.059, blue: 0.102)       // #0E0F1A
    static let surface = Color(red: 0.094, green: 0.102, blue: 0.165)   // #181A2A
    static let surface2 = Color(red: 0.13, green: 0.14, blue: 0.22)
    static let orange = Color(red: 1.0, green: 0.42, blue: 0.24)        // #FF6B3D
    static let violet = Color(red: 0.486, green: 0.361, blue: 1.0)      // #7C5CFF
    static let mint = Color(red: 0.24, green: 1.0, blue: 0.69)          // #3DFFB0
    static let text = Color.white
    static let dim = Color.white.opacity(0.55)
    static let faint = Color.white.opacity(0.08)
    static let brand = LinearGradient(colors: [orange, violet], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let brandAngular = AngularGradient(colors: [orange, violet, mint, orange], center: .center)
}

/// Pasala's mark: an original paper plane with the dotted trail of the pass. Drawn in a 100×100 box.
enum MarkGeometry {
    static let nose = CGPoint(x: 86, y: 14)
    static let upperWing = [nose, CGPoint(x: 14, y: 45), CGPoint(x: 44, y: 56)]
    static let lowerWing = [nose, CGPoint(x: 44, y: 56), CGPoint(x: 57, y: 86)]
    static let keel = [nose, CGPoint(x: 44, y: 56), CGPoint(x: 46, y: 71)]
    static let trailStart = CGPoint(x: 12, y: 90), trailControl = CGPoint(x: 16, y: 64), trailEnd = CGPoint(x: 38, y: 62)
    static let dotRadius: CGFloat = 3.75, spacing: CGFloat = 11.5

    static let lavender = Color(red: 0.894, green: 0.863, blue: 1.0)      // #E4DCFF
    static let fold = Color(red: 0.725, green: 0.651, blue: 1.0)          // #B9A6FF

    static func point(_ t: CGFloat) -> CGPoint {
        let u = 1 - t
        return CGPoint(x: u * u * trailStart.x + 2 * u * t * trailControl.x + t * t * trailEnd.x,
                       y: u * u * trailStart.y + 2 * u * t * trailControl.y + t * t * trailEnd.y)
    }
    static let dots: [CGPoint] = {
        var pts: [CGPoint] = [trailStart], acc: CGFloat = 0, next: CGFloat = spacing, prev = trailStart
        for i in 1...400 {
            let p = point(CGFloat(i) / 400)
            acc += hypot(p.x - prev.x, p.y - prev.y); prev = p
            if acc >= next { pts.append(p); next += spacing }
        }
        return pts
    }()

    static func path(_ pts: [CGPoint], _ k: CGFloat) -> Path {
        var p = Path(); p.move(to: CGPoint(x: pts[0].x * k, y: pts[0].y * k))
        for q in pts.dropFirst() { p.addLine(to: CGPoint(x: q.x * k, y: q.y * k)) }
        p.closeSubpath(); return p
    }

    static func draw(in ctx: GraphicsContext, size: CGSize, trail: Color = Theme.mint) {
        let k = min(size.width, size.height) / 100
        for d in dots {
            ctx.fill(Path(ellipseIn: CGRect(x: (d.x - dotRadius) * k, y: (d.y - dotRadius) * k, width: 2 * dotRadius * k, height: 2 * dotRadius * k)), with: .color(trail))
        }
        for (pts, color, w) in [(lowerWing, lavender, 4.0), (keel, fold, 3.0), (upperWing, Color.white, 4.0)] {
            let p = path(pts, k)
            ctx.fill(p, with: .color(color))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: w * k, lineJoin: .round))
        }
    }

    /// Monochrome template image for the menu bar (wings in steps of alpha so the fold still reads).
    static func template(size: CGFloat = 18) -> NSImage {
        let img = NSImage(size: NSSize(width: size, height: size), flipped: true) { r in
            let k = r.width / 100
            func poly(_ pts: [CGPoint], _ alpha: CGFloat, _ w: CGFloat) {
                let b = NSBezierPath(); b.move(to: NSPoint(x: pts[0].x * k, y: pts[0].y * k))
                for q in pts.dropFirst() { b.line(to: NSPoint(x: q.x * k, y: q.y * k)) }
                b.close(); b.lineJoinStyle = .round; b.lineWidth = w * k
                NSColor.black.withAlphaComponent(alpha).setFill(); NSColor.black.withAlphaComponent(alpha).setStroke()
                b.fill(); b.stroke()
            }
            NSColor.black.setFill()
            for d in dots { NSBezierPath(ovalIn: NSRect(x: (d.x - 5) * k, y: (d.y - 5) * k, width: 10 * k, height: 10 * k)).fill() }
            poly(lowerWing, 0.55, 4); poly(keel, 0.35, 3); poly(upperWing, 1, 4)
            return true
        }
        img.isTemplate = true
        return img
    }
}

struct PasalaMark: View {
    var size: CGFloat = 20
    var trail: Color = Theme.mint
    var body: some View {
        Canvas { ctx, sz in MarkGeometry.draw(in: ctx, size: sz, trail: trail) }
            .frame(width: size, height: size)
    }
}

/// The app icon as a view: gradient tile with the plane.
struct PasalaTile: View {
    var size: CGFloat = 40
    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.23, style: .continuous).fill(Theme.brand)
            .frame(width: size, height: size)
            .overlay(PasalaMark(size: size))
            .clipShape(RoundedRectangle(cornerRadius: size * 0.23, style: .continuous))
    }
}

struct PlaneGlyph: View {   // the mark on dark surfaces (island, headers)
    var size: CGFloat = 14
    var body: some View { PasalaMark(size: size * 1.35) }
}

struct DeviceGlyph: View {
    let platform: String
    var online = false
    var size: CGFloat = 34
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Circle().fill(Theme.surface2)
                .overlay(Circle().strokeBorder(online ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.faint), lineWidth: 1.5))
                .overlay(Image(systemName: platform == "mac" ? "laptopcomputer" : "iphone.gen3")
                    .font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(.white.opacity(0.9)))
                .frame(width: size, height: size)
            Circle().fill(online ? Theme.mint : Color.gray.opacity(0.6))
                .frame(width: size * 0.28, height: size * 0.28)
                .overlay(Circle().stroke(Theme.ink, lineWidth: 2))
                .shadow(color: online ? Theme.mint.opacity(0.8) : .clear, radius: 4)
        }
    }
}

/// Thumbnail for a file on disk (QuickLook), falling back to the type icon.
struct Thumb: View {
    let url: URL?
    let mime: String
    var corner: CGFloat = 12
    @State private var image: NSImage?

    var body: some View {
        // The image fills the offered size and is clipped to it. See HISTORY.md#thumb-clip
        Rectangle().fill(Theme.surface2)
            .overlay {
                if let image {
                    Image(nsImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: icon).font(.system(size: 22, weight: .medium)).foregroundStyle(Theme.brand)
                }
            }
            .overlay {
                if Mime.kind(mime) == "video", image != nil {
                    Image(systemName: "play.fill").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                        .padding(6).background(.black.opacity(0.45), in: Circle())
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .task(id: url) { await load() }
    }

    var icon: String {
        switch Mime.kind(mime) {
        case "image": return "photo"
        case "video": return "film"
        case "audio": return "waveform"
        case "pdf": return "doc.richtext"
        default: return "doc"
        }
    }

    func load() async {
        guard let url, FileManager.default.fileExists(atPath: url.path) else { image = nil; return }
        if let cached = ThumbCache.shared.object(forKey: url as NSURL) { image = cached; return }
        let req = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: 320, height: 320), scale: 2, representationTypes: .thumbnail)
        if let rep = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: req) {
            ThumbCache.shared.setObject(rep.nsImage, forKey: url as NSURL)
            image = rep.nsImage
        }
    }
}

enum ThumbCache { static let shared = NSCache<NSURL, NSImage>() }

struct ProgressRing: View {
    let progress: Double
    var size: CGFloat = 22
    var line: CGFloat = 3
    var body: some View {
        ZStack {
            Circle().stroke(Theme.faint, lineWidth: line)
            Circle().trim(from: 0, to: max(0.02, progress))
                .stroke(Theme.brandAngular, style: StrokeStyle(lineWidth: line, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.25), value: progress)
        }.frame(width: size, height: size)
    }
}

struct GlowButton: View {
    let title: String
    var icon: String? = nil
    var prominent = true
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.system(size: 12, weight: .bold)) }
                Text(title).font(.system(size: 12.5, weight: .semibold, design: .rounded))
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
            .foregroundStyle(.white)
            .background {
                if prominent { Capsule().fill(Theme.brand).shadow(color: Theme.violet.opacity(hover ? 0.7 : 0.35), radius: hover ? 12 : 6) }
                else { Capsule().fill(Color.white.opacity(hover ? 0.14 : 0.08)) }
            }
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .animation(.easeOut(duration: 0.15), value: hover)
    }
}

extension Date {
    var relative: String {
        let f = RelativeDateTimeFormatter(); f.locale = Locale(identifier: "es_AR"); f.unitsStyle = .short
        return f.localizedString(for: self, relativeTo: .now)
    }
}
