package com.kai.agent.ui

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NativeCharacterMotionTest {
    private val manifest=JSONObject(sequenceOf(File("src/main/assets/characters/native/manifest.json"),File("app/src/main/assets/characters/native/manifest.json")).first{it.exists()}.readText())
    private val specs=listOf("kai","spark","nova","sage").map{CharacterSkinSpec.decode(it,manifest.getJSONObject(it).getJSONObject("spec"))}
    @Test fun weightsAreFiniteAndNormalized(){for(s in specs)for(y in 0..s.height.toInt() step 29)for(x in 0..s.width.toInt() step 31){val w=NativeCharacterMotion.weights(s,x.toFloat(),y.toFloat());assertTrue(w.all{it.isFinite()&&it>=0&&it<=1});assertTrue(w.sum()<=1.00001f)}}
    @Test fun neutralSkinKeepsExactSourceCoordinates(){for(s in specs){val b=NativeCharacterMotion.bones(s,CharacterSkinPose());for(y in 0..s.height.toInt() step 31)for(x in 0..s.width.toInt() step 37){val q=NativeCharacterMotion.deform(s,x.toFloat(),y.toFloat(),NativeCharacterMotion.weights(s,x.toFloat(),y.toFloat()),b,NativeCharacterMotion.matrix(0f,0f,0f));assertEquals(x.toFloat(),q[0],.001f);assertEquals(y.toFloat(),q[1],.001f)}}}
    @Test fun everyStateKeepsAllLimbsAndTorsoAtRest(){for(s in specs)for(state in KaiCharacterActivity.entries)for(n in 0..90){val t=n/20f;val p=NativeCharacterMotion.pose(s,t,state,0f,t,true);assertEquals(0f,p.body,0f);assertEquals(0f,p.left,0f);assertEquals(0f,p.elbow,0f);assertEquals(0f,p.wrist,0f);assertEquals(0f,p.right,0f);assertEquals(0f,p.rightElbow,0f);assertEquals(0f,p.rightWrist,0f)}}
    @Test fun wholeCharacterFloatsWithoutStretchingFeet(){for(s in specs){val root=NativeCharacterMotion.matrix(0f,0f,0f,0f,-3f);val bones=NativeCharacterMotion.bones(s,CharacterSkinPose());for(y in listOf(s.shoulder[1],s.feet)){val q=NativeCharacterMotion.deform(s,s.width/2,y,NativeCharacterMotion.weights(s,s.width/2,y),bones,root);assertEquals(y-3f,q[1],.001f)}}}
    @Test fun greetingStartsAndSettlesWithArmsAtRest(){for(s in specs)for(t in listOf(0f,2.85f,3.2f)){val p=NativeCharacterMotion.pose(s,t,KaiCharacterActivity.IDLE,0f,t,false);assertEquals(0f,p.left,.001f);assertEquals(0f,p.elbow,.001f);assertEquals(0f,p.wrist,.001f)}}
    @Test fun neckDeformationIsContinuousAcrossFormerCut(){for(s in specs){val p=NativeCharacterMotion.pose(s,1.3f,KaiCharacterActivity.LISTENING,0f,1.3f,false);val b=NativeCharacterMotion.bones(s,p);val root=NativeCharacterMotion.matrix(s.width/2,s.feet,p.body,0f,p.lift);for(n in 0..((s.head[1]-s.head[0])*2).toInt()){val y=s.head[0]+n*.5f;val x=s.width/2;val a=NativeCharacterMotion.deform(s,x,y,NativeCharacterMotion.weights(s,x,y),b,root);val c=NativeCharacterMotion.deform(s,x,y+.5f,NativeCharacterMotion.weights(s,x,y+.5f),b,root);assertTrue(hypot(c[0]-a[0],c[1]-a[1])<.9)}}}
    @Test fun armBonesCannotDragTheFaceOrFeet(){for(s in specs){assertTrue(NativeCharacterMotion.weights(s,s.eye[0],s.eye[1]).drop(1).all{it==0f});for(x in 0..s.width.toInt() step 7)assertTrue(NativeCharacterMotion.weights(s,x.toFloat(),s.feet).drop(1).all{it==0f})}}
}
