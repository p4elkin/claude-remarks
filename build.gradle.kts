import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.sasha"
version = "0.8.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")
        testFramework(TestFrameworkType.Platform)
        // ShowDiffAction lives in lib/modules/intellij.platform.vcs.impl.jar, not lib/app.jar.
        // Settled by compiling: the import does not resolve without this line.
        bundledModule("intellij.platform.vcs.impl")
        // The markdown preview extension point, its BrowserPipe and MarkdownHtmlPanel. All five
        // classes preview/PreviewRemarkExtension.kt needs sit in plugins/markdown/lib/markdown.jar.
        // The plugin.xml dependency on this plugin is optional, so the plugin still loads when a
        // person disables markdown; this compile-time line is not optional, because the code has to
        // build against those classes either way.
        bundledPlugin("org.intellij.plugins.markdown")
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
    }

    pluginVerification {
        // INTERNAL_API_USAGES used to be subtracted here too, for SegmentedButton.component, reached
        // from the tag chip row in RemarkInputPanel. Phase 11 deleted the chip row, so that usage is
        // gone and the subtraction went with it. The verifier now reports no internal API usage at
        // all, which is why a new one would fail the build again rather than pass unnoticed. Keep it
        // that way: if a future change needs an internal API, weigh it as its own decision instead of
        // reviving this subtraction.
        //
        // EXPERIMENTAL_API_USAGES is subtracted for the markdown preview.
        // MarkdownHtmlPanel.getBrowserPipe(), getProject() and getVirtualFile() each carry
        // @ApiStatus.Experimental, and preview/PreviewRemarkExtension.kt calls all three. The pipe is
        // the only way a plugin can hear what the person selected in the rendered preview, and the
        // interface exposes no non-experimental route to it — getBrowserPipe() IS the published
        // route, the alternative being the panel's PREVIEW_BROWSER user-data key, which sits on an
        // @ApiStatus.Internal class and would be worse. So the choice was to drop the preview remark
        // feature or to accept this.
        //
        // EXPERIMENTAL_API_USAGES has a second reason since phase 11, and both have to go before the
        // subtraction can. ui/AnswerPopup.kt builds a JBHtmlPane to draw an answer as rendered
        // markdown, and JBHtmlPane carries @ApiStatus.Experimental at class level, as do
        // JBHtmlPaneStyleConfiguration and JBHtmlPaneConfiguration. It is the pane the platform's own
        // quick documentation popup uses, and there is no non-experimental pane that renders the
        // <pre><code>, <kbd> and <details> an answer needs. So removing the markdown preview later
        // does NOT make this line removable — check the popup too.
        //
        // What this costs: a future experimental-API use is no longer reported either. If those
        // getters ever lose the annotation, delete this half of the expression.
        //
        // MarkdownBrowserPreviewExtension itself carries @ApiStatus.Obsolete, and that needs no
        // subtraction at all: the verifier has no processor for obsolete API and the Gradle plugin
        // has no matching failure level.
        failureLevel = FailureLevel.ALL - FailureLevel.EXPERIMENTAL_API_USAGES
    }
}
