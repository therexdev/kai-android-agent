package com.kai.agent.ui

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import kotlin.math.*

internal data class CharacterSkinLayer(val bitmap: Bitmap, val bounds: RectF)
internal class CharacterSkinAssets(val spec: CharacterSkinSpec, val skin: Bitmap, val layers: Map<String,CharacterSkinLayer>) {
    val columns=64;val rows=80
    val rest=FloatArray((columns+1)*(rows+1)*2)
    val weights=FloatArray((columns+1)*(rows+1)*7)
    init {for(y in 0..rows)for(x in 0..columns){val i=y*(columns+1)+x;val xx=spec.width*x/columns;val yy=spec.height*y/rows;rest[i*2]=xx;rest[i*2+1]=yy;NativeCharacterMotion.weights(spec,xx,yy).copyInto(weights,i*7)}}
    companion object {
        fun load(context: Context,id: String): CharacterSkinAssets {
            val safe=characterAssetId(id);val j=JSONObject(context.assets.open("characters/native/manifest.json").bufferedReader().use {it.readText()}).getJSONObject(safe)
            val options=BitmapFactory.Options().apply {inScaled=false}
            fun bitmap(part: String)=context.assets.open("characters/native/$safe/$part.webp").use {requireNotNull(BitmapFactory.decodeStream(it,null,options))}
            val rects=j.getJSONObject("layers");val parts=listOf("eyes","attentive","brows","smile","mouth","cape")
            val layers=parts.filter {rects.has(it)}.associateWith {name->val a=rects.getJSONArray(name);CharacterSkinLayer(bitmap(name),RectF(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat()))}
            require(layers.keys.containsAll(listOf("eyes","brows","smile","mouth")))
            return CharacterSkinAssets(CharacterSkinSpec.decode(safe,j.getJSONObject("spec")),bitmap("skin"),layers)
        }
    }
}
internal data class CharacterSkinSnapshot(val id: String,val state: KaiCharacterActivity,val audible: Boolean,val motion: Boolean,val active: Boolean,val frames: Int,val mouth: Float,val pose: CharacterSkinPose,val greetingAge: Float,val pending: Boolean)

/** One native bitmap-mesh draw plus cached face layers. No WebView, shader bridge or network. */
internal class NativeCharacterView(context: Context,val art: CharacterSkinAssets): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val vertices=FloatArray(art.rest.size)
    private val androidMatrix=Matrix()
    private val birth=SystemClock.uptimeMillis()
    private var state=KaiCharacterActivity.IDLE
    private var audible=false;private var motion=false;private var foreground=false;private var released=false
    private var greeting=0;private var greetingAt=-10f;private var stateSince=0f;private var changedAt=-10f
    private var fromPose: CharacterSkinPose?=null;private var lastPose=CharacterSkinPose()
    private var mouth=0f;private var lastDraw=-1f;private var frames=0;private var revision=0;private var drawnRevision=-1
    private var scheduled=false
    private val tick=object:Runnable {override fun run(){scheduled=false;if(released)return;invalidate();schedule()}}
    init {layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;setWillNotDraw(false)}
    private fun time()=(SystemClock.uptimeMillis()-birth)/1000f
    private fun active()=foreground&&isAttachedToWindow&&windowVisibility==VISIBLE&&isShown&&!released
    fun present(activity: KaiCharacterActivity,speechAudible: Boolean,animate: Boolean,isForeground: Boolean,greetingCount: Int) {
        if(released)return
        if(state==activity&&audible==speechAudible&&motion==animate&&foreground==isForeground&&greeting==greetingCount)return
        val t=time()
        if(state!=activity){fromPose=lastPose;changedAt=t;stateSince=t;state=activity}
        if(greeting!=greetingCount){greeting=greetingCount;greetingAt=t;fromPose=lastPose;changedAt=t}
        audible=speechAudible;motion=animate;foreground=isForeground;revision++
        removeCallbacks(tick);scheduled=false
        if(!active()){removeCallbacks(tick);scheduled=false}else{invalidate();schedule()}
    }
    private fun schedule(){
        if(scheduled||!active())return
        val wait=if(motion)33L else if(time()-greetingAt<2.85f)((2.86f-(time()-greetingAt))*1000).toLong().coerceAtLeast(1) else return
        scheduled=true;postDelayed(tick,wait)
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();invalidate();schedule()}
    override fun onDetachedFromWindow(){removeCallbacks(tick);scheduled=false;super.onDetachedFromWindow()}
    fun release(){released=true;removeCallbacks(tick);scheduled=false}
    fun snapshot()=CharacterSkinSnapshot(art.spec.id,state,audible,motion,active(),frames,mouth,lastPose,time()-greetingAt,drawnRevision!=revision)
    private fun transform(canvas: Canvas,m: FloatArray){androidMatrix.setValues(floatArrayOf(m[0],m[2],m[4],m[1],m[3],m[5],0f,0f,1f));canvas.concat(androidMatrix)}
    private fun layer(canvas: Canvas,name: String,alpha: Float=1f){art.layers[name]?.let {paint.alpha=(alpha.coerceIn(0f,1f)*255).roundToInt();canvas.drawBitmap(it.bitmap,null,it.bounds,paint);paint.alpha=255}}
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(released||width<=0||height<=0)return
        val s=art.spec;val t=time();val dt=if(lastDraw<0)0f else (t-lastDraw).coerceIn(0f,.08f);lastDraw=t
        var pose=NativeCharacterMotion.pose(s,if(motion)t else 0f,state,stateSince,if(motion)t-greetingAt else -1f,audible&&motion)
        if(!motion)pose=pose.copy(head=when(state){KaiCharacterActivity.LISTENING->-2f;KaiCharacterActivity.THINKING->2f;else->0f},body=0f,lift=0f)
        if(motion)fromPose?.let {pose=it.blend(pose,NativeCharacterMotion.smooth(0f,.4f,t-changedAt))}
        lastPose=pose
        val root=NativeCharacterMotion.matrix(s.width/2,s.feet,0f,0f,pose.lift)
        val bones=NativeCharacterMotion.bones(s,pose)
        NativeCharacterMotion.fillMesh(s,art.rest,art.weights,bones,root,vertices)
        val scale=min(width/s.view[2],height/s.view[3]);canvas.save();canvas.translate((width-scale*s.view[2])/2-scale*s.view[0],(height-scale*s.view[3])/2-scale*s.view[1]);canvas.scale(scale,scale)
        canvas.save();transform(canvas,root);layer(canvas,"cape");canvas.restore()
        paint.alpha=255;canvas.drawBitmapMesh(art.skin,art.columns,art.rows,vertices,0,null,0,paint)
        canvas.save();transform(canvas,NativeCharacterMotion.multiply(root,bones[0]))
        val bt=(t+when(s.id){"kai"->.7f;"nova"->1.4f;"sage"->2.1f;else->0f})%5.7f
        val blink=if(motion)1f-.94f*max(0f,1f-abs(bt-3.7f)/.13f).pow(2) else 1f
        canvas.save();canvas.translate(pose.gazeX,pose.gazeY);canvas.scale(1f,blink,s.eye[0],s.eye[1]);layer(canvas,if(s.id=="kai"&&state in setOf(KaiCharacterActivity.LISTENING,KaiCharacterActivity.THINKING,KaiCharacterActivity.REVIEW))"attentive" else "eyes");canvas.restore()
        canvas.save();canvas.translate(0f,if(!motion&&t-greetingAt<2.85f)-4f else -2f*max(0f,1f-abs(t-greetingAt-1.2f)/1.2f));layer(canvas,"brows");canvas.restore()
        mouth=if(motion)mouth+((if(audible)1f else 0f)-mouth)*(1f-exp(-dt/(if(audible).07f else .11f))) else 0f
        layer(canvas,"smile",1f-mouth)
        val syllable=.25f+.75f*abs(sin(t*11.8f)*sin(t*7.3f+.8f));canvas.save();canvas.scale(.92f+.08f*syllable,.24f+.76f*syllable,s.mouth[0],s.mouth[1]);layer(canvas,"mouth",mouth);canvas.restore()
        canvas.restore();canvas.restore()
        frames++;drawnRevision=revision;schedule()
    }
}
