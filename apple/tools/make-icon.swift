// Renders docs/brand/pasala-icon.svg into a 1024 macOS icon PNG (artwork inset like Apple's grid).
import AppKit
let a = CommandLine.arguments
let svg = NSImage(contentsOf: URL(fileURLWithPath: a[1]))!
let size: CGFloat = 1024
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let shadow = NSShadow(); shadow.shadowColor = NSColor.black.withAlphaComponent(0.25); shadow.shadowBlurRadius = 18; shadow.shadowOffset = NSSize(width: 0, height: -8); shadow.set()
svg.draw(in: NSRect(x: 100, y: 100, width: size - 200, height: size - 200))
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: a[2]))
