package(
    default_visibility = [
        "//src:__subpackages__",
    ],
)

java_library(
    name = "standalone",
    srcs = glob(["*.java"]),
    deps = [
        "//src/main/java:actions",
        "//src/main/java:analysis-exec-rules-skyframe",
        "//src/main/java:buildtool-runtime",
        "//src/main/java:common",
        "//src/main/java:io",
        "//src/main/java:os_util",
        "//src/main/java:packages",
        "//src/main/java:shell",
        "//src/main/java:util",
        "//src/main/java:vfs",
        "//third_party:guava",
    ],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java:__pkg__"],
)
