package com.example.meetomxh.supabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned Admin contracts. Unknown states stay explicit; absent fields never become success. */
public final class AdminModels {
    private AdminModels() { }
    public enum AccountState { ACTIVE, SUSPENDED, DELETING, UNKNOWN;
        public static AccountState parse(String value) {
            try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
            catch (Exception ignored) { return UNKNOWN; }
        }
    }
    public static final class Capabilities {
        public final boolean systemAdmin, moderator, reviewReports;
        public Capabilities(JSONObject value) throws JSONException {
            if(value.getInt("contract_version")!=2) throw new JSONException("Admin contract version");
            systemAdmin=value.getBoolean("system_admin"); moderator=value.getBoolean("moderator");
            reviewReports=value.getBoolean("can_review_reports");
        }
    }
    public static final class Report {
        public final String id, kind, state, evidenceStatus, mediaStatus;
        public final boolean canClaim;
        public final JSONArray permittedActions, media;
        public final JSONObject target;
        public Report(JSONObject value) throws JSONException {
            id=value.getString("id"); kind=value.getString("target_kind"); state=value.getString("state");
            canClaim=value.getBoolean("can_claim"); evidenceStatus=value.optString("evidence_status","unknown");
            mediaStatus=value.optString("media_status","unknown");
            permittedActions=value.optJSONArray("permitted_actions"); media=value.optJSONArray("media"); target=value.optJSONObject("target");
        }
    }
    public static final class Content {
        public final String id, kind, state;
        public final long version;
        public Content(JSONObject value) throws JSONException {
            id=value.getString("id"); kind=value.getString("kind"); state=value.getString("state"); version=value.optLong("version",-1);
        }
    }
    public static final class Account {
        public final String id;
        public final AccountState state;
        public Account(JSONObject value) throws JSONException {
            id=value.getString("id"); state=AccountState.parse(value.getString("state"));
        }
    }
    public static final class Receipt {
        public final JSONObject value;
        public final String requestId, state, actionId;
        public final boolean replayed;
        public Receipt(JSONObject value) throws JSONException {
            this.value=value; requestId=value.getString("request_id"); state=value.getString("state");
            actionId=value.optString("action_id",""); replayed=value.getBoolean("replayed");
        }
    }
    public static final class AuditEntry {
        public final String id, actorId, actionId, reportId, operation, reason, oldState, newState;
        public AuditEntry(JSONObject value) throws JSONException {
            id=value.getString("id"); actorId=value.optString("actor_id",""); actionId=value.optString("action_id","");
            reportId=value.optString("report_id",""); operation=value.getString("operation"); reason=value.optString("reason","");
            oldState=value.optString("old_state",""); newState=value.optString("new_state","");
        }
    }
    public static final class Page {
        public final JSONArray items;
        public final boolean hasMore;
        public final JSONObject nextCursor;
        public Page(JSONObject value) throws JSONException {
            if(value.getInt("contract_version")!=2) throw new JSONException("Admin contract version");
            items=value.getJSONArray("items"); hasMore=value.getBoolean("has_more"); nextCursor=value.optJSONObject("next_cursor");
            if(hasMore && (items.length()==0 || nextCursor==null)) throw new JSONException("Admin cursor missing");
            if(hasMore){nextCursor.getString("id");nextCursor.getString("created_at");}
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);item.getString("id");item.getString("created_at");}
        }
    }
}
