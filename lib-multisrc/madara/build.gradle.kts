plugins {
    id("lib-multisrc")
}

baseVersionCode = 40

dependencies {
    api(project(":lib:cryptoaes"))
    api(project(":lib:i18n"))
}
