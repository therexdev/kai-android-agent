package com.kai.agent.runtime
import org.json.JSONObject
import org.json.JSONArray
/** Canonical findings outlive individual step projections, but never a privacy invalidation. */
object RuntimeObservations {
    fun read(run: ExecutionRun, id: String, offset: Int): String {
        require(offset in 0..2_000_000)
        if(id=="observations") return JSONObject().put("source","phone_run_observations").put("entries",JSONArray(run.observations.takeLast(80).map {
            JSONObject().put("entryId","obs:"+it.id).put("stepId",it.stepId).put("characters",it.content.length).put("preview",it.content.take(120))
        })).toString()
        val observation=run.observations.firstOrNull { "obs:"+it.id==id } ?: return JSONObject().put("status","NOT_FOUND").toString()
        val end=minOf(observation.content.length,offset+3000)
        return JSONObject().put("source","phone_run_observations").put("entryId",id).put("offset",offset).put("totalCharacters",observation.content.length)
            .put("excerpt",if(offset<observation.content.length) observation.content.substring(offset,end) else "")
            .put("nextOffset",if(end<observation.content.length) end else JSONObject.NULL).toString()
    }
}
