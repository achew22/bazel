package(default_visibility = ["//src/test:__subpackages__"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

sh_binary(
    name = "swiftstdlibtoolwrapper",
    srcs = ["swiftstdlibtoolwrapper.sh"],
    data = ["//src/tools/xcode/realpath:realpath_genrule"],
)
