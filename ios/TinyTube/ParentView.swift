import SwiftUI
import WebKit
import TinyTubeCore

/* Parent mode: real YouTube, in a web view, behind the gate.
   Counterpart of ParentActivity.

   THIS IS ONLY EVER REACHED THROUGH THE GATE. `MainView` presents it after
   `Gate.authenticate` passes or `ChallengeView` does. Don't add another caller.

   NOTHING INSIDE PARENT MODE NEEDS ITS OWN GATE. The approved list and the
   settings open straight from this bar — getting here already required one, and
   asking again would be asking the same question twice.

   `YouTubeUrls` decides where this may browse and what counts as a channel
   page, and is pure so it is tested on Linux. Its allowlist is deliberately
   WIDER than `Player`'s: sign-in hosts are reachable here and must never be
   reachable from the child's screen. */
struct ParentView: View {

    let onClose: () -> Void

    @State private var currentURL = YouTubeUrls.parentStart
    @State private var approvedHere = false
    @State private var showApproved = false
    @State private var showSettings = false
    @State private var reloadToken = UUID()

    /* The + is live only on an actual channel page — a URL starting /@handle
       or /channel/. Anywhere else there is nothing to approve. */
    private var channelId: String? {
        guard YouTubeUrls.isChannelPage(currentURL) else { return nil }
        return YouTubeUrls.channelIdFromURL(currentURL)
    }

    private var isApproved: Bool {
        guard let channelId else { return false }
        return ChannelStore.contains(channelId: channelId)
    }

    var body: some View {
        VStack(spacing: 0) {
            bar
            Divider().overlay(Color.white.opacity(0.1))
            ParentWebView(startURL: YouTubeUrls.parentStart, currentURL: $currentURL)
                .id(reloadToken)
        }
        .background(Color.black)
        .sheet(isPresented: $showApproved) {
            ApprovedChannelsView { channel in
                showApproved = false
                currentURL = channel.url
                reloadToken = UUID()
            }
        }
        .sheet(isPresented: $showSettings) { SettingsView() }
    }

    private var bar: some View {
        HStack(spacing: 14) {
            Button("Done", action: onClose)
                .font(.subheadline.weight(.semibold))

            Spacer()

            if let channelId {
                Button {
                    toggle(channelId)
                } label: {
                    Image(systemName: isApproved ? "minus.circle.fill" : "plus.circle.fill")
                        .font(.title2)
                        .foregroundStyle(isApproved ? Color.red : Color.green)
                }
                .accessibilityLabel(isApproved ? "Remove channel" : "Approve channel")
            }

            Button { showApproved = true } label: {
                Image(systemName: "list.bullet").font(.title3)
            }
            .accessibilityLabel("Approved channels")

            Button { showSettings = true } label: {
                Image(systemName: "gearshape").font(.title3)
            }
            .accessibilityLabel("Settings")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func toggle(_ channelId: String) {
        if ChannelStore.contains(channelId: channelId) {
            /* Removing takes the channel's videos and its watch history with
               it — ChannelStore.remove does all three. */
            ChannelStore.remove(channelId: channelId)
        } else {
            /* Approving a channel approves its FUTURE uploads, which no adult
               has seen. That is the deal this app makes and it is the weakest
               point in it. */
            ChannelStore.add(
                channelId: channelId,
                title: YouTubeUrls.handleFromURL(currentURL) ?? channelId,
                handle: YouTubeUrls.handleFromURL(currentURL),
                now: Int64(Date().timeIntervalSince1970 * 1000)
            )
        }
        approvedHere.toggle()
    }
}

/* YouTube itself. Separate from the player's web view because the two have
   different allowlists on purpose, and sharing one configuration is how a
   sign-in host ends up reachable from the child's screen. */
struct ParentWebView: UIViewRepresentable {

    let startURL: String
    @Binding var currentURL: String

    func makeCoordinator() -> Coordinator { Coordinator(currentURL: $currentURL) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        /* An adult is driving; autoplaying every thumbnail they scroll past is
           just noise and data. */
        config.mediaTypesRequiringUserActionForPlayback = .all

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true

        /* Google refuses to sign in when it can tell it is inside a web view.
           WKWebView's default user agent is Safari's with `Version/… Safari/…`
           missing, and that absence is the tell — so add it back. Appended via
           applicationNameForUserAgent rather than replacing the whole string,
           so the OS version and WebKit build stay truthful.

           A workaround for a deliberate restriction, not a supported path. See
           BrowserUserAgent. */
        web.configuration.applicationNameForUserAgent = BrowserUserAgent.safariSuffix

        if let url = URL(string: startURL) {
            web.load(URLRequest(url: url))
        }
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        @Binding var currentURL: String

        init(currentURL: Binding<String>) { _currentURL = currentURL }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor action: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = action.request.url?.absoluteString else {
                decisionHandler(.cancel)
                return
            }
            /* Matched on the parsed host by YouTubeUrls, never on a substring.
               Wider than the player's list because sign-in lives across several
               Google hosts — and narrower than "anything", because this is
               still not a general browser. */
            decisionHandler(YouTubeUrls.isParentBrowsable(url) ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            if let url = webView.url?.absoluteString { currentURL = url }
        }

        /* Sign-in's last step opens a window. With windows unsupported,
           window.open() returns null and the flow simply stops there — which is
           what "hangs at the last step" looks like from the outside. Load the
           target in this same web view instead of opening anything. */
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for action: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = action.request.url?.absoluteString,
               YouTubeUrls.isParentBrowsable(url) {
                webView.load(action.request)
            }
            return nil
        }
    }
}
