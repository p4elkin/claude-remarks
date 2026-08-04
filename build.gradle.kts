import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.sasha"
version = "0.6.0"

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
        // The verifier's own conclusion is "Compatible. 1 usage of internal API". The one usage is
        // SegmentedButton.component, whose getter carries @get:ApiStatus.Internal, reached from
        // RemarkInputPanel so that Enter submits while focus sits on the tag chip row. The interface
        // exposes no public way to the Swing component, so the alternatives were to lose that key or
        // to accept this. Without the line below the Gradle plugin's default failure levels turn the
        // note into a failed build, so `verifyPlugin` exits non-zero on a plugin the verifier itself
        // calls compatible.
        //
        // What this costs: a future internal-API use is no longer reported either. If that getter is
        // ever replaced by a public one, delete this block rather than growing it.
        //
        // EXPERIMENTAL_API_USAGES is subtracted for a second, separate reason: the markdown preview.
        // MarkdownHtmlPanel.getBrowserPipe(), getProject() and getVirtualFile() each carry
        // @ApiStatus.Experimental, and preview/PreviewRemarkExtension.kt calls all three. The pipe is
        // the only way a plugin can hear what the person selected in the rendered preview, and the
        // interface exposes no non-experimental route to it — getBrowserPipe() IS the published
        // route, the alternative being the panel's PREVIEW_BROWSER user-data key, which sits on an
        // @ApiStatus.Internal class and would be worse. So the choice was to drop the preview remark
        // feature or to accept this, the same trade already accepted once for SegmentedButton.
        //
        // What this costs: a future experimental-API use is no longer reported either. If those
        // getters ever lose the annotation, delete this half of the expression.
        //
        // MarkdownBrowserPreviewExtension itself carries @ApiStatus.Obsolete, and that needs no
        // subtraction at all: the verifier has no processor for obsolete API and the Gradle plugin
        // has no matching failure level.
        failureLevel = FailureLevel.ALL - FailureLevel.INTERNAL_API_USAGES - FailureLevel.EXPERIMENTAL_API_USAGES
    }
}
