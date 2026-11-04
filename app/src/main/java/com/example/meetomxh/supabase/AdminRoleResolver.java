package com.example.meetomxh.supabase;

import org.json.JSONArray;
import org.json.JSONObject;

/** Resolves the app entry surface from a server-authorized admin RPC. */
public final class AdminRoleResolver {
    public enum Destination { ADMIN, SOCIAL, LOGIN, RETRY }

    public static final class Result {
        public final Destination destination;
        public final String message;
        private Result(Destination destination, String message) {
            this.destination = destination;
            this.message = message;
        }
    }

    private AdminRoleResolver() { }

    public static Result resolve() {
        if (SessionManager.getInstance().getCurrentUser() == null)
            return new Result(Destination.LOGIN, "Phiên đăng nhập không còn hoạt động.");
        try {
            JSONObject dashboard = RpcClient.object("admin_dashboard", Api.obj());
            boolean valid=dashboard != null && dashboard.has("users_total") && dashboard.has("posts_total")
                    && dashboard.opt("recent_reports") instanceof JSONArray
                    && dashboard.opt("recent_actions") instanceof JSONArray;
            return interpret(valid, null);
        } catch (ErrorMapper.HttpError error) {
            return interpret(false, error);
        } catch (Exception error) {
            return interpret(false, error);
        }
    }

    static Result interpret(boolean validDashboard, Exception failure) {
        if (failure instanceof ErrorMapper.HttpError) {
            ErrorMapper.HttpError error=(ErrorMapper.HttpError)failure;
            if (error.status == 401) return new Result(Destination.LOGIN, "Phiên đăng nhập đã hết hạn.");
            if (error.status == 403 && ("system_admin_required".equals(error.serverMessage)
                    || "system_admin_required".equals(error.code)))
                return new Result(Destination.SOCIAL, null);
            return new Result(Destination.RETRY, ErrorMapper.message(error));
        }
        if (failure != null) return new Result(Destination.RETRY, ErrorMapper.message(failure));
        if (!validDashboard)
            return new Result(Destination.RETRY, "Phản hồi xác minh quyền không hợp lệ.");
        return new Result(Destination.ADMIN, null);
    }
}
