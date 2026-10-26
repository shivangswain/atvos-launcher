// Renders an HTML or SVG file to a PNG of an exact size with the WebKit of macOS. It makes the images in public/ and
// ../docs/images/ from card.html and logo.svg:
//
//   swiftc -O render.swift -o /tmp/render
//   /tmp/render card.html ../public/social/og.png 1200 630
//   /tmp/render card.html ../../docs/images/social-preview.png 1280 640
//   /tmp/render logo.svg ../public/social/logo.png 512 512
import Cocoa
import WebKit

final class Waiter: NSObject, WKNavigationDelegate {
    var done = false
    func webView(_ w: WKWebView, didFinish n: WKNavigation!) { done = true }
}

func spin(_ s: Double) { RunLoop.main.run(until: Date().addingTimeInterval(s)) }

let args = CommandLine.arguments
guard args.count == 5, let width = Int(args[3]), let height = Int(args[4]) else {
    print("usage: render INPUT OUTPUT.png WIDTH HEIGHT"); exit(2)
}
let input = URL(fileURLWithPath: args[1]).absoluteURL
NSApplication.shared.setActivationPolicy(.prohibited)
let frame = NSRect(x: 0, y: 0, width: width, height: height)
let window = NSWindow(contentRect: frame, styleMask: [.borderless], backing: .buffered, defer: false)
window.setFrameOrigin(NSPoint(x: -10000, y: -10000))
let web = WKWebView(frame: frame)
web.setValue(false, forKey: "drawsBackground") // transparent where the page draws nothing
window.contentView = web
window.orderFrontRegardless()
let waiter = Waiter()
web.navigationDelegate = waiter
if input.pathExtension == "svg" {
    // An SVG fills the whole image.
    let svg = try! Data(contentsOf: input).base64EncodedString()
    web.loadHTMLString("<html><body style='margin:0'><img src='data:image/svg+xml;base64,\(svg)' style='display:block;width:100vw;height:100vh'></body></html>", baseURL: nil)
} else {
    web.loadFileURL(input, allowingReadAccessTo: input.deletingLastPathComponent())
}
while !waiter.done { spin(0.1) }
spin(1.0) // fonts
var image: NSImage?
let config = WKSnapshotConfiguration()
config.rect = frame
web.takeSnapshot(with: config) { i, _ in image = i }
while image == nil { spin(0.1) }
// Draws the snapshot (2x on a Retina display) into a bitmap of exactly WIDTH x HEIGHT pixels.
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8, samplesPerPixel: 4,
                           hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current?.imageInterpolation = .high
image!.draw(in: frame)
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))
print("\(args[2]): \(width) x \(height)")
