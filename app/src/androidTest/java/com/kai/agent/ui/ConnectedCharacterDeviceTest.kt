package com.kai.agent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native renderer; synthetic state starts no model, recognizer or microphone. */
@RunWith(AndroidJUnit4::class)
class ConnectedCharacterDeviceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private var activity:ComponentActivity?=null
    private val state=mutableStateOf(KaiUiState(selectedCharacterId="spark",reducedMotion=false))
    private val output get()=File(i.targetContext.getExternalFilesDir(null),"qa-native-0411").apply{mkdirs()}
    @Test fun allFourCharactersKeepTheirBodiesAtRestWhenTappedAndWorking()=fixture{
        for(id in listOf("spark","nova","sage","kai")){
            i.runOnMainSync{state.value=state.value.copy(selectedCharacterId=id)};waitReady(id);screenshot("$id-neutral.png")
            for(voice in listOf(KaiVoiceState.IDLE,KaiVoiceState.THINKING,KaiVoiceState.SPEAKING)) {
                i.runOnMainSync{state.value=state.value.copy(voiceState=voice,speechAudible=voice==KaiVoiceState.SPEAKING)}
                tap()
                waitUntil(10_000){val sample=snapshot();val p=sample.pose
                    assertEquals(0f,p.body,0f);assertEquals(0f,p.left,0f);assertEquals(0f,p.elbow,0f);assertEquals(0f,p.wrist,0f)
                    assertEquals(0f,p.right,0f);assertEquals(0f,p.rightElbow,0f);assertEquals(0f,p.rightWrist,0f)
                    sample.greetingAge>1f
                }
            }
            screenshot("$id-resting-body.png")
        }
    }
    @Test fun playbackGatesTheMouthAndReducedMotionStopsTheFrameLoop()=fixture{
        waitReady("spark");i.runOnMainSync{state.value=state.value.copy(voiceState=KaiVoiceState.SPEAKING,speechAudible=true)}
        waitUntil(10_000){snapshot().mouth>.7f};screenshot("spark-speaking.png")
        i.runOnMainSync{state.value=state.value.copy(speechAudible=false)};waitUntil(10_000){snapshot().mouth<.04f}
        i.runOnMainSync{state.value=state.value.copy(reducedMotion=true)};waitUntil(10_000){snapshot().let{!it.motion&&!it.pending}}
        val before=snapshot().frames;Thread.sleep(600);assertEquals(before,snapshot().frames)
        tap();waitUntil(10_000){!snapshot().pending};assertEquals(0f,snapshot().pose.left,.001f);screenshot("spark-reduced-motion.png")
    }
    @Test fun backgroundStopsRenderingAndResumeKeepsTheSameMesh()=fixture{scenario->
        waitReady("spark");val original=renderer();scenario.moveToState(Lifecycle.State.CREATED);waitUntil(10_000){!snapshot().active};val before=snapshot().frames;Thread.sleep(600);assertEquals(before,snapshot().frames)
        scenario.moveToState(Lifecycle.State.RESUMED);waitUntil(10_000){snapshot().let{it.active&&it.frames>before}};assertSame(original,renderer())
    }
    @Test fun softwareCanvasAlsoDrawsACompleteConnectedCharacter()=fixture{
        waitReady("spark");val v=renderer();var bitmap:Bitmap?=null
        i.runOnMainSync{bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap!!))}
        bitmap!!.let{b->File(output,"spark-software-canvas.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};assertTrue("Software renderer omitted fur",furSamples(b)>300);b.recycle()}
    }
    private fun fixture(block:(ActivityScenario<ComponentActivity>)->Unit){
        require(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        val actions=Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,arrayOf(KaiUiActions::class.java)){proxy,method,args->when(method.name){"equals"->proxy===args?.get(0);"hashCode"->System.identityHashCode(proxy);"toString"->"NativeRigFixture";else->null}} as KaiUiActions
        ActivityScenario.launch(ComponentActivity::class.java).use{s->s.onActivity{a->activity=a;a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.setContent{KaiApp(state.value,actions)}};block(s)};activity=null
    }
    private fun waitUntil(timeout:Long,condition:()->Boolean){val deadline=System.nanoTime()+timeout*1_000_000;while(!condition()){if(System.nanoTime()>deadline)throw AssertionError("Native character condition timed out after $timeout ms");Thread.sleep(20)}}
    private fun tap(){val v=renderer();val location=IntArray(2);var x=0f;var y=0f;i.runOnMainSync{v.getLocationOnScreen(location);x=location[0]+v.width/2f;y=location[1]+v.height/2f};val now=android.os.SystemClock.uptimeMillis();for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){val e=android.view.MotionEvent.obtain(now,now+if(action==android.view.MotionEvent.ACTION_UP)60 else 0,action,x,y,0);try{i.sendPointerSync(e)}finally{e.recycle()}}}
    private fun renderer():NativeCharacterView{var result:NativeCharacterView?=null;i.runOnMainSync{fun visit(v:View){if(v is NativeCharacterView)result=v;if(v is ViewGroup)for(n in 0 until v.childCount)visit(v.getChildAt(n))};activity?.window?.decorView?.let(::visit)};return requireNotNull(result)}
    private fun snapshot():CharacterSkinSnapshot{val v=renderer();var s:CharacterSkinSnapshot?=null;i.runOnMainSync{s=v.snapshot()};return requireNotNull(s)}
    private fun waitReady(id:String)=waitUntil(60_000){runCatching{snapshot().let{it.id==id&&it.frames>0&&!it.pending}}.getOrDefault(false)}
    private fun furSamples(b:Bitmap):Int{val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height);var n=0;for(y in 0 until b.height step 2)for(x in 0 until b.width step 2){val c=pixels[y*b.width+x];val r=c shr 16 and 255;val g=c shr 8 and 255;val blue=c and 255;if(r>150&&g in 55..210&&blue<g*.7&&r>g*1.2)n++};return n}
    private fun screenshot(name:String){val b=requireNotNull(i.uiAutomation.takeScreenshot());try{File(output,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};if(name.startsWith("spark-"))assertTrue("Spark body absent in $name",furSamples(b)>500)}finally{b.recycle()}}
}
