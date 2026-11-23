package com.kai.agent.ui

import org.json.JSONObject
import kotlin.math.*

internal data class CharacterSkinSpec(
    val id: String, val width: Float, val height: Float, val view: FloatArray,
    val neck: FloatArray, val head: FloatArray, val shoulder: FloatArray, val elbow: FloatArray,
    val wrist: FloatArray, val armEdge: List<FloatArray>, val eye: FloatArray,
    val mouth: FloatArray, val feet: Float, val vigor: Float,
) {
    companion object {
        fun decode(id: String, j: JSONObject): CharacterSkinSpec {
            fun array(key: String) = j.getJSONArray(key).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            val edges = j.getJSONArray("armEdge")
            return CharacterSkinSpec(id, j.getDouble("w").toFloat(), j.getDouble("h").toFloat(), array("view"),
                array("neck"), array("head"), array("shoulder"), array("elbow"), array("wrist"),
                List(edges.length()) { n -> edges.getJSONArray(n).let { a -> floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat()) } },
                array("eye"), array("mouth"), j.getDouble("feet").toFloat(), j.getDouble("vigor").toFloat())
        }
    }
}
internal data class CharacterSkinPose(
    val head: Float = 0f, val body: Float = 0f, val lift: Float = 0f,
    val left: Float = 0f, val elbow: Float = 0f, val wrist: Float = 0f,
    val right: Float = 0f, val rightElbow: Float = 0f, val rightWrist: Float = 0f,
    val gazeX: Float = 0f, val gazeY: Float = 0f,
) {
    fun blend(to: CharacterSkinPose, f: Float): CharacterSkinPose {
        fun m(a: Float,b: Float) = a+(b-a)*f
        return CharacterSkinPose(m(head,to.head),m(body,to.body),m(lift,to.lift),m(left,to.left),m(elbow,to.elbow),m(wrist,to.wrist),
            m(right,to.right),m(rightElbow,to.rightElbow),m(rightWrist,to.rightWrist),m(gazeX,to.gazeX),m(gazeY,to.gazeY))
    }
}
/** Android-free skin math, shared by the native view and its geometry tests. */
internal object NativeCharacterMotion {
    fun smooth(a: Float,b: Float,x: Float): Float {val t=((x-a)/(b-a)).coerceIn(0f,1f);return t*t*t*(10f+t*(-15f+6f*t))}
    fun matrix(x: Float,y: Float,degrees: Float,dx: Float=0f,dy: Float=0f): FloatArray {
        val a=degrees*PI.toFloat()/180f;val c=cos(a);val s=sin(a)
        return floatArrayOf(c,s,-s,c,x-c*x+s*y+dx,y-s*x-c*y+dy)
    }
    fun multiply(a: FloatArray,b: FloatArray)=floatArrayOf(a[0]*b[0]+a[2]*b[1],a[1]*b[0]+a[3]*b[1],a[0]*b[2]+a[2]*b[3],a[1]*b[2]+a[3]*b[3],a[0]*b[4]+a[2]*b[5]+a[4],a[1]*b[4]+a[3]*b[5]+a[5])
    fun point(m: FloatArray,x: Float,y: Float)=floatArrayOf(m[0]*x+m[2]*y+m[4],m[1]*x+m[3]*y+m[5])
    private fun edge(s: CharacterSkinSpec,y: Float): Float {
        for(i in 1 until s.armEdge.size) {val a=s.armEdge[i-1];val b=s.armEdge[i];if(y<b[0])return a[1]+(b[1]-a[1])*((y-a[0])/(b[0]-a[0])).coerceIn(0f,1f)}
        return s.armEdge.last()[1]
    }
    fun weights(s: CharacterSkinSpec,x: Float,y: Float): FloatArray {
        val h=1f-smooth(s.head[0],s.head[1],y);val start=s.armEdge.first()[0];val end=s.armEdge.last()[0]
        val arm=smooth(start,start+70f,y)*(1f-smooth(end-40f,end,y));val e=edge(s,y)
        val l=arm*(1f-smooth(e-5f,e+5f,x));val r=arm*smooth(s.width-e-5f,s.width-e+5f,x)
        val vx=s.wrist[0]-s.elbow[0];val vy=s.wrist[1]-s.elbow[1];val len=sqrt(vx*vx+vy*vy)
        val along=((x-s.elbow[0])*vx+(y-s.elbow[1])*vy)/len;val ra=((s.width-x-s.elbow[0])*vx+(y-s.elbow[1])*vy)/len
        val lower=smooth(-40f,45f,along);val hand=smooth(len-24f,len+24f,along);val rl=smooth(-40f,45f,ra);val rh=smooth(len-24f,len+24f,ra)
        return floatArrayOf((1f-l-r)*h,l*(1f-lower),l*lower*(1f-hand),l*lower*hand,r*(1f-rl),r*rl*(1f-rh),r*rl*rh)
    }
    fun pose(s: CharacterSkinSpec,t: Float,state: KaiCharacterActivity,since: Float,wave: Float,audible: Boolean): CharacterSkinPose {
        var head=.65f*sin(t*.67f)+.30f*sin(t*1.13f)
        val lift=-2.7f*(.5f-.5f*cos(t*PI.toFloat()/2.25f))
        var gx=0f;var gy=0f
        when(state) {
            KaiCharacterActivity.LISTENING -> head=-2.5f+.35f*sin(t*.8f)
            KaiCharacterActivity.THINKING,KaiCharacterActivity.WORKING,KaiCharacterActivity.REVIEW -> {head=2.7f+.45f*sin(t*.65f);gy=-9f;gx=5f}
            KaiCharacterActivity.ERROR -> head=-2.4f
            KaiCharacterActivity.SPEAKING -> if(audible) head+=.7f*sin(t*3.2f)*sin(t*.9f)
            else -> Unit
        }
        // Greeting is facial only. Limbs and torso remain in the source artwork's resting pose.
        return CharacterSkinPose(head=head,lift=lift,gazeX=gx,gazeY=gy)
    }
    fun bones(s: CharacterSkinSpec,p: CharacterSkinPose): Array<FloatArray> {
        val l=matrix(s.shoulder[0],s.shoulder[1],p.left);val f=multiply(l,matrix(s.elbow[0],s.elbow[1],p.elbow));val hand=multiply(f,matrix(s.wrist[0],s.wrist[1],p.wrist))
        val r=matrix(s.width-s.shoulder[0],s.shoulder[1],p.right);val rf=multiply(r,matrix(s.width-s.elbow[0],s.elbow[1],p.rightElbow));val rh=multiply(rf,matrix(s.width-s.wrist[0],s.wrist[1],p.rightWrist))
        return arrayOf(matrix(s.neck[0],s.neck[1],p.head),l,f,hand,r,rf,rh)
    }
    fun deform(s: CharacterSkinSpec,x: Float,y: Float,w: FloatArray,b: Array<FloatArray>,root: FloatArray): FloatArray {
        var dx=0f;var dy=0f
        for(k in 0..6)if(w[k]!=0f){val m=b[k];dx+=w[k]*((m[0]-1f)*x+m[2]*y+m[4]);dy+=w[k]*(m[1]*x+(m[3]-1f)*y+m[5])}
        val xx=x+dx;val yy=y+dy;val plant=1f
        return floatArrayOf(xx+(root[0]*xx+root[2]*yy+root[4]-xx)*plant,yy+(root[1]*xx+root[3]*yy+root[5]-yy)*plant)
    }
    fun fillMesh(s: CharacterSkinSpec,rest: FloatArray,w: FloatArray,b: Array<FloatArray>,root: FloatArray,out: FloatArray) {
        for(i in 0 until rest.size/2){val x=rest[i*2];val y=rest[i*2+1];var dx=0f;var dy=0f
            for(k in 0..6){val weight=w[i*7+k];if(weight!=0f){val m=b[k];dx+=weight*((m[0]-1f)*x+m[2]*y+m[4]);dy+=weight*(m[1]*x+(m[3]-1f)*y+m[5])}}
            val xx=x+dx;val yy=y+dy;val plant=1f
            out[i*2]=xx+(root[0]*xx+root[2]*yy+root[4]-xx)*plant;out[i*2+1]=yy+(root[1]*xx+root[3]*yy+root[5]-yy)*plant
        }
    }
}
