import SwiftUI
import WebKit
import TinyTubeCore

/* The locked-down web view. Counterpart of PlayerActivity's WebView half.

   `Player` decides what page is loaded and where it may navigate, and is pure
   so it is tested on Linux. This wires it to WebKit.

   THE ALLOWLIST IS MATCHED ON THE PARSED HOST, never on a substring of the URL
   — which is what makes `youtube.com.attacker.example` and
   `https://www.youtube.com@attacker.example/` refusals rather than matches.
   `Player.isPlayerURL` does that; nothing here second-guesses it.

   THE BRIDGE IS A SHIM, and this is the one real difference from Android. The
   page is shared line for line, and it calls a GLOBAL OBJECT — `Bridge.onState(…)`,
   `Bridge.onEnded()`, `Bridge.onError(…)`. Android injects exactly that with
   `addJavascriptInterface`. WKWebView has no equivalent: it offers
   `window.webkit.messageHandlers.<name>.postMessage(…)` and nothing else. So a
   user script defines `Bridge` in those terms before the page runs, and the
   shared page stays untouched. If a method is ever added to the page's side of
   that contract it has to be added to the shim too, or the call is a silent
   `undefined is not a function` inside a web view with no console attached. */
struct PlayerWebView: UIViewRepresentable {

    let videoId: String
    let onState: (Int) -> Void
    let onEnded: () -> Void
    let onError: () -> Void

    /* YouTube's IFrame API state codes. The same two Android names, for the
       same reason: `state == 1` at a call site says nothing. */
    enum PlayerState {
        static let playing = 1
        static let paused = 2
    }

    private static let bridgeShim = """
    window.Bridge = {
      onState:  function (s) { window.webkit.messageHandlers.bridge.postMessage({ fn: 'state', value: s }); },
      onEnded:  function ()  { window.webkit.messageHandlers.bridge.postMessage({ fn: 'ended' }); },
      onError:  function (c) { window.webkit.messageHandlers.bridge.postMessage({ fn: 'error', value: String(c) }); },
      onAd:     function (a) { window.webkit.messageHandlers.bridge.postMessage({ fn: 'ad', value: !!a }); }
    };
    """

    func makeCoordinator() -> Coordinator {
        Coordinator(onState: onState, onEnded: onEnded, onError: onError)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        /* Inline, or iOS hands the video to its own full-screen player — which
           brings its own controls, outside this app's overlay entirely. */
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(
            source: Self.bridgeShim,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
        controller.add(context.coordinator, name: "bridge")
        config.userContentController = controller

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.scrollView.bounces = false
        /* No long-press preview: it offers to open a link, which is a way out
           of the app from the child's screen. */
        web.allowsLinkPreview = false

        context.coordinator.load(videoId, into: web)
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        guard context.coordinator.loaded != videoId else { return }
        context.coordinator.load(videoId, into: web)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        private let onState: (Int) -> Void
        private let onEnded: () -> Void
        private let onError: () -> Void
        private(set) var loaded: String?

        init(onState: @escaping (Int) -> Void,
             onEnded: @escaping () -> Void,
             onError: @escaping () -> Void) {
            self.onState = onState
            self.onEnded = onEnded
            self.onError = onError
        }

        func load(_ id: String, into web: WKWebView) {
            /* Refused rather than built. `pageFor` re-validates the id because
               it is interpolated into a JS string literal — the third check on
               the same value, after the Worker and after `Uploads.parse`. */
            guard let page = Player.pageFor(videoId: id) else { return }
            loaded = id
            web.loadHTMLString(page, baseURL: URL(string: Player.origin))
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor action: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = action.request.url?.absoluteString else {
                /* The initial loadHTMLString has no URL to check. */
                decisionHandler(.allow)
                return
            }
            decisionHandler(Player.isPlayerURL(url) ? .allow : .cancel)
        }

        func userContentController(
            _ controller: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard message.name == "bridge",
                  let payload = message.body as? [String: Any],
                  let fn = payload["fn"] as? String
            else { return }

            switch fn {
            case "state":
                /* JS numbers arrive as NSNumber; a Double cast covers both
                   integral and non-integral encodings. */
                if let n = payload["value"] as? NSNumber { onState(n.intValue) }
            case "ended": onEnded()
            case "error": onError()
            default: break
            }
        }
    }
}
