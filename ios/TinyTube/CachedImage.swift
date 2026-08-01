import SwiftUI

/* A poster frame or an avatar, drawn from ImageStore.

   The drop-in replacement for `AsyncImage`, which every one of these call sites
   used to be. It is a separate view rather than a modifier because the load is
   asynchronous and the result has to live somewhere across a redraw.

   `.resizable().scaledToFill()` is applied here rather than by the caller,
   because all three sites did exactly that and for the same reason: a bare
   resizable stretches a non-square image instead of cropping it. The caller
   still supplies its own placeholder — a rounded rectangle for a poster, a
   circle for an avatar — and still does its own framing and clipping.

   ⚠️ THE TASK IS KEYED ON THE URL. A grid cell is reused as it scrolls, so
   without the id a tile that scrolled away and came back as a different video
   would keep the previous poster until something else forced a redraw. This is
   the counterpart of Thumbnails.tagFor on Android, which solves the same
   recycled-view problem the way a RecyclerView needs it solved. */
struct CachedImage<Placeholder: View>: View {

    let url: String?
    @ViewBuilder var placeholder: () -> Placeholder

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            /* Cleared first, so a reused cell shows its placeholder rather than
               the previous URL's picture while this one loads. */
            image = nil
            image = await ImageStore.load(url)
        }
    }
}
