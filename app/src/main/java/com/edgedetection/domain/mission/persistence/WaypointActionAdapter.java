package com.edgedetection.domain.mission.persistence;

import com.edgedetection.domain.mission.WaypointAction;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

public class WaypointActionAdapter implements JsonSerializer<WaypointAction>, JsonDeserializer<WaypointAction> {
    @Override
    public JsonElement serialize(WaypointAction src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject result = new JsonObject();
        result.addProperty("type", src.getClass().getSimpleName());
        if (src instanceof WaypointAction.RotateGimbal) {
            result.addProperty("pitchDegrees", ((WaypointAction.RotateGimbal) src).pitchDegrees);
        }
        return result;
    }

    @Override
    public WaypointAction deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonNull()) return null;
        
        JsonObject jsonObject = json.getAsJsonObject();
        JsonElement typeElement = jsonObject.get("type");
        
        if (typeElement != null) {
            String type = typeElement.getAsString();
            switch (type) {
                case "Hover": return new WaypointAction.Hover();
                case "TakePhoto": return new WaypointAction.TakePhoto();
                case "StartVideo": return new WaypointAction.StartVideo();
                case "StopVideo": return new WaypointAction.StopVideo();
                case "RotateGimbal":
                    JsonElement pitchElement = jsonObject.get("pitchDegrees");
                    float pitch = pitchElement != null ? pitchElement.getAsFloat() : 0.0f;
                    return new WaypointAction.RotateGimbal(pitch);
            }
        }
        
        // Fallback for data saved without 'type' field
        if (jsonObject.has("pitchDegrees")) {
            return new WaypointAction.RotateGimbal(jsonObject.get("pitchDegrees").getAsFloat());
        }
        
        return new WaypointAction.Hover();
    }
}
