package com.example.meetomxh.supabase;




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
            AdminModels.Capabilities capabilities=new AdminModels.Capabilities(RpcClient.object("admin_get_capabilities",Api.obj()));
            return new Result(capabilities.systemAdmin?Destination.ADMIN:Destination.SOCIAL,null);
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
            return new Result(Destination.RETRY, new AdminRepository.AdminError(error).message);
        }
        if (failure != null) return new Result(Destination.RETRY, new AdminRepository.AdminError(failure).message);
        if (!validDashboard)
            return new Result(Destination.RETRY, "Phản hồi xác minh quyền không hợp lệ.");
        return new Result(Destination.ADMIN, null);
    }
}
