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
    @State private var showApproved = false
    @State private var showSettings = false
    @State private var reloadToken = UUID()
    @State private var working = false
    @State private var message: String?
    /* Recomputed on every URL change rather than read from the database inside
       `body` — a view's body must not do I/O on every redraw. */
    @State private var approvedHere = false

    /* The + is live only on an actual channel page — a URL starting /@handle or
       /channel/. Note what this does NOT require: a channel ID. Most of YouTube
       uses handles, and a handle cannot be turned into an id on the device, so
       gating the button on having an id already would hide it on nearly every
       channel page there is. That was the bug. The id is resolved when the
       button is TAPPED. */
    private var onChannelPage: Bool { YouTubeUrls.isChannelPage(currentURL) }

    var body: some View {
        VStack(spacing: 0) {
            bar
            if let message {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 6)
            }
            Divider().overlay(Color.white.opacity(0.1))
            ParentWebView(startURL: YouTubeUrls.parentStart, currentURL: $currentURL)
                .id(reloadToken)
        }
        .background(Color.black)
        .onChange(of: currentURL) { _ in refreshApprovedState() }
        .sheet(isPresented: $showApproved, onDismiss: refreshApprovedState) {
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

            /* Always present, dimmed when it can't act. An ImageButton that
               disappears on a non-channel page leaves nothing to explain why —
               a parent standing on a search page would just find the control
               gone. Android dims rather than hides for exactly this reason. */
            Button(action: toggle) {
                Image(systemName: approvedHere ? "minus.circle.fill" : "plus.circle.fill")
                    .font(.title2)
                    .foregroundStyle(approvedHere ? Color.red : Color.green)
                    .opacity(onChannelPage && !working ? 1 : 0.35)
            }
            .disabled(!onChannelPage || working)
            .accessibilityLabel(approvedHere ? "Remove channel" : "Approve channel")

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

    /* Whether the channel on screen is already approved.
     *
     * A /channel/UC… URL answers this outright. A /@handle URL does not — the
     * id is only known after resolving — so the handle recorded at approval
     * time is checked instead, which is exactly why `channels.handle` exists. */
    private func refreshApprovedState() {
        message = nil
        if let id = YouTubeUrls.channelIdFromURL(currentURL) {
            approvedHere = ChannelStore.contains(channelId: id)
        } else if let handle = YouTubeUrls.handleFromURL(currentURL) {
            approvedHere = ChannelStore.find(handle: handle) != nil
        } else {
            approvedHere = false
        }
    }

    private func toggle() {
        guard onChannelPage, !working else { return }

        /* Already approved and identifiable without a round trip: remove it. */
        if let id = YouTubeUrls.channelIdFromURL(currentURL),
           ChannelStore.contains(channelId: id) {
            remove(id)
            return
        }
        if let handle = YouTubeUrls.handleFromURL(currentURL),
           let existing = ChannelStore.find(handle: handle) {
            remove(existing.id)
            return
        }

        working = true
        message = "Working out which channel this is…"
        let url = currentURL

        Task {
            let resolved = await ChannelResolver.resolve(url: url)
            await MainActor.run {
                working = false
                guard let resolved else {
                    message = "Couldn't identify a channel on this page."
                    return
                }
                /* Approving a channel approves its FUTURE uploads, which no
                   adult has seen. That is the deal this app makes and it is the
                   weakest point in it. */
                ChannelStore.add(
                    channelId: resolved.id,
                    title: resolved.title,
                    handle: YouTubeUrls.handleFromURL(url),
                    avatarURL: resolved.avatarURL,
                    now: Int64(Date().timeIntervalSince1970 * 1000)
                )
                message = "Approved \(resolved.title)."
                /* Re-derive rather than just flipping the flag: resolving hit
                   the network, and the parent may have navigated away while it
                   ran. */
                refreshApprovedState()
            }
        }
    }

    private func remove(_ id: String) {
        /* Takes the channel's videos and its watch history with it. */
        ChannelStore.remove(channelId: id)
        refreshApprovedState()
        message = "Removed."
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

        /* Google refuses to sign in when it can tell it is inside a web view.
           WKWebView's default user agent is Safari's with `Version/… Safari/…`
           missing, and that absence is the tell — so add it back. Appended via
           applicationNameForUserAgent rather than replacing the whole string,
           so the OS version and WebKit build stay truthful.

           Set on the CONFIGURATION before the web view is created. Assigning it
           to an existing web view's configuration has no effect — the
           configuration is copied at init. */
        config.applicationNameForUserAgent = BrowserUserAgent.safariSuffix

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true

        context.coordinator.observe(web)

        if let url = URL(string: startURL) {
            web.load(URLRequest(url: url))
        }
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        coordinator.stopObserving()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        @Binding var currentURL: String
        private var observation: NSKeyValueObservation?

        init(currentURL: Binding<String>) { _currentURL = currentURL }

        /* YouTube's mobile site is a SINGLE-PAGE APP. Tapping a channel swaps
           the content with pushState and never loads a page, so didFinish does
           not fire and the tracked URL stays wherever the last real navigation
           left it — which is why the approve button never lit up.

           Observing `url` catches those history updates. It is the iOS
           counterpart of Android's doUpdateVisitedHistory, which exists in
           ParentActivity for precisely the same reason and says so. */
        func observe(_ web: WKWebView) {
            observation = web.observe(\.url, options: [.new]) { [weak self] _, change in
                guard let url = change.newValue??.absoluteString else { return }
                DispatchQueue.main.async { self?.currentURL = url }
            }
        }

        func stopObserving() {
            observation?.invalidate()
            observation = nil
        }

        deinit { stopObserving() }

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
