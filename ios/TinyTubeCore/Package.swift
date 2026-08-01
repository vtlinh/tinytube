// swift-tools-version: 5.9
import PackageDescription

/* The decisions that can put the wrong thing in front of a child, with no
   UIKit, no WebKit and no Foundation-on-a-device in them.

   It is a package rather than a folder in the app target for the same reason
   the Kotlin originals are Android-free: a plain toolchain can run it. That
   means these tests execute on Linux, in this repository's CI, without a Mac
   and without a simulator — which is the difference between a port that is
   claimed to match the Android app and one that is shown to.

   Every file here has a counterpart under
   android/app/src/main/java/dev/vtlinh/tinytube/, and the tests are ported
   alongside so the two platforms are held to the same promises rather than to
   two readings of the same intent. When one changes, change both. */
let package = Package(
    name: "TinyTubeCore",
    products: [
        .library(name: "TinyTubeCore", targets: ["TinyTubeCore"]),
    ],
    targets: [
        .target(name: "TinyTubeCore"),
        .testTarget(name: "TinyTubeCoreTests", dependencies: ["TinyTubeCore"]),
    ]
)
