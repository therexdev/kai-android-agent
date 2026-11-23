package com.kai.agent.upgrade

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.ui.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterPaintDiagnosticDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun inspectNativeAndDocumentPaint() {
        UpgradeFixture.requireEmulator()
        val output = File(UpgradeFixture.context.getExternalFilesDir(null), "qa-character-diagnostic").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    KaiTheme(true) { Box(Modifier.fillMaxSize().background(Color(0xff101014)), contentAlignment = Alignment.Center) {
                        CharacterAvatar(builtInKaiCharacters.first(), KaiVoiceState.IDLE, Modifier.size(240.dp), reducedMotion = true)
                    } }
                }
            }
            compose.waitUntil(120_000) { compose.onAllNodesWithTag("character-rig-live-kai", useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            lateinit var web: WebView
            val native = JSONObject()
            scenario.onActivity { activity ->
                fun find(view: View): WebView? {
                    if (view is WebView) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                web = requireNotNull(find(activity.window.decorView))
                val point = IntArray(2); web.getLocationOnScreen(point)
                native.put("width",web.width).put("height",web.height).put("x",point[0]).put("y",point[1])
                    .put("shown",web.isShown).put("alpha",web.alpha.toDouble()).put("visibility",web.visibility)
                    .put("contentHeight",web.contentHeight).put("scale",web.scale.toDouble()).put("hardware",web.isHardwareAccelerated)
                    .put("userAgent",web.settings.userAgentString).put("focus",web.hasWindowFocus()).put("layoutWidth",web.layoutParams?.width).put("layoutHeight",web.layoutParams?.height)
            }
            fun eval(script: String): String {
                val result=AtomicReference<String>();val latch=CountDownLatch(1)
                scenario.onActivity { web.evaluateJavascript(script) { result.set(it);latch.countDown() } }
                check(latch.await(30,TimeUnit.SECONDS)) { "JavaScript inspection timed out" }
                return result.get()
            }
            fun capture(name: String) {
                val ready = CountDownLatch(1)
                scenario.onActivity { web.postVisualStateCallback(1, object : WebView.VisualStateCallback() { override fun onComplete(id: Long) { web.invalidate(); ready.countDown() } }) }
                check(ready.await(15,TimeUnit.SECONDS))
                Thread.sleep(300)
                val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                File(output,"$name-screen.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
                scenario.onActivity {
                    val bitmap=Bitmap.createBitmap(web.width.coerceAtLeast(1),web.height.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    web.draw(Canvas(bitmap))
                    var painted=0
                    for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) if ((bitmap.getPixel(x,y) ushr 24)>10) painted++
                    native.put("${name}NativePaintedSamples",painted)
                    File(output,"$name-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
            }
            val geometry = """JSON.stringify((function(){function info(e){if(!e)return null;var r=e.getBoundingClientRect(),s=getComputedStyle(e);return {x:r.x,y:r.y,width:r.width,height:r.height,display:s.display,opacity:s.opacity,visibility:s.visibility,position:s.position,transform:s.transform};}return {innerWidth:innerWidth,innerHeight:innerHeight,html:info(document.documentElement),body:info(document.body),svg:info(document.querySelector('svg')),firstGroup:info(document.querySelector('svg g')),bodyClass:document.body.className,hrefs:Array.from(document.querySelectorAll('svg image')).map(function(i){return i.getAttribute('href')||i.getAttribute('xlink:href')})};})())"""
            val report=JSONObject().put("native",native).put("dom",JSONObject(JSONTokener(eval(geometry)).nextValue() as String))
            capture("01-initial")
            eval("(function(){window.__probe={state:'pending'};var i=new Image();i.onload=function(){window.__probe={state:'loaded',width:i.naturalWidth,height:i.naturalHeight};};i.onerror=function(){window.__probe={state:'error',url:i.src};};i.src=document.querySelector('svg image').getAttribute('href');return true;})()")
            var probe=JSONObject()
            compose.waitUntil(30_000) { probe=JSONObject(JSONTokener(eval("JSON.stringify(window.__probe)")).nextValue() as String);probe.optString("state")!="pending" }
            report.put("textureProbe",probe)
            scenario.onActivity {
                web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                web.requestLayout();web.invalidate()
            }
            compose.waitForIdle();Thread.sleep(500)
            report.put("afterMatchParent",JSONObject(JSONTokener(eval(geometry)).nextValue() as String))
            capture("01b-match-parent")
            eval("(function(){var d=document.createElement('div');d.id='sentinel';d.style.cssText='position:fixed;left:0;top:0;width:80px;height:80px;background:red;z-index:99999';document.body.appendChild(d);return true;})()")
            compose.waitForIdle();capture("02-sentinel")
            eval("document.querySelector('svg').style.cssText='width:240px!important;height:240px!important;display:block;visibility:visible;opacity:1;position:absolute;left:0;top:0';")
            compose.waitForIdle();capture("03-explicit-size")
            report.put("afterExplicitSize",JSONObject(JSONTokener(eval(geometry)).nextValue() as String))
            File(output,"paint-diagnostic.json").writeText(report.toString(2))
        }
    }
}
