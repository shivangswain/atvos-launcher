// Turns a line of text into SVG path data with CoreText, kerning included. It makes the atvOS logo (logo.svg) from
// Inter, because a favicon cannot load a font:
//
//   swiftc -O textpath.swift -o /tmp/textpath
//   /tmp/textpath InterDisplay-Bold atvOS -0.02
//
// It prints the PostScript name of the font that it used, then the width and height of the outlines (font units at
// size 1000, baseline at the bottom), then the path data.
import CoreText
import Foundation

let args = CommandLine.arguments
guard args.count >= 3 else { print("usage: textpath FONT TEXT [TRACKING_EM]"); exit(2) }
let size: CGFloat = 1000
let font = CTFontCreateWithName(args[1] as CFString, size, nil)
let tracking = args.count > 3 ? CGFloat(Double(args[3]) ?? 0) * size : 0
let attrs: [NSAttributedString.Key: Any] = [.init(kCTFontAttributeName as String): font, .init(kCTKernAttributeName as String): tracking]
let line = CTLineCreateWithAttributedString(NSAttributedString(string: args[2], attributes: attrs))
let path = CGMutablePath()
for run in CTLineGetGlyphRuns(line) as! [CTRun] {
    let n = CTRunGetGlyphCount(run)
    var glyphs = [CGGlyph](repeating: 0, count: n), points = [CGPoint](repeating: .zero, count: n)
    CTRunGetGlyphs(run, CFRange(location: 0, length: n), &glyphs)
    CTRunGetPositions(run, CFRange(location: 0, length: n), &points)
    let runFont = (CTRunGetAttributes(run) as NSDictionary)[kCTFontAttributeName as String] as! CTFont
    for i in 0..<n {
        if let g = CTFontCreatePathForGlyph(runFont, glyphs[i], nil) {
            path.addPath(g, transform: CGAffineTransform(translationX: points[i].x, y: points[i].y))
        }
    }
}
let box = path.boundingBoxOfPath
// SVG has y down: flip, and move the outlines to (0, 0).
var flip = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: -box.minX, ty: box.maxY)
let svg = path.copy(using: &flip)!
var d = ""
func f(_ v: CGFloat) -> String { String(format: "%.1f", v).replacingOccurrences(of: ".0", with: "") }
svg.applyWithBlock { e in
    let p = e.pointee.points
    switch e.pointee.type {
    case .moveToPoint: d += "M\(f(p[0].x)) \(f(p[0].y))"
    case .addLineToPoint: d += "L\(f(p[0].x)) \(f(p[0].y))"
    case .addQuadCurveToPoint: d += "Q\(f(p[0].x)) \(f(p[0].y)) \(f(p[1].x)) \(f(p[1].y))"
    case .addCurveToPoint: d += "C\(f(p[0].x)) \(f(p[0].y)) \(f(p[1].x)) \(f(p[1].y)) \(f(p[2].x)) \(f(p[2].y))"
    case .closeSubpath: d += "Z"
    @unknown default: break
    }
}
print(CTFontCopyPostScriptName(font) as String)
print(f(box.width), f(box.height), f(box.maxY)) // width, height, and the height above the baseline
print(d)
