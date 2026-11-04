package com.example.meetomxh.supabase;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class AdminRoleResolverTest {
    @Test public void validDashboardResolvesAdmin() {
        assertEquals(AdminRoleResolver.Destination.ADMIN,AdminRoleResolver.interpret(true,null).destination);
    }

    @Test public void onlyExplicitSystemAdminDenialResolvesSocial() {
        assertEquals(AdminRoleResolver.Destination.SOCIAL,AdminRoleResolver.interpret(false,
                new ErrorMapper.HttpError(403,"PT403","system_admin_required")).destination);
        assertEquals(AdminRoleResolver.Destination.SOCIAL,AdminRoleResolver.interpret(false,
                new ErrorMapper.HttpError(403,"system_admin_required","")).destination);
    }

    @Test public void unrelatedPermissionFailureDoesNotAssumeRegularUser() {
        assertEquals(AdminRoleResolver.Destination.RETRY,AdminRoleResolver.interpret(false,
                new ErrorMapper.HttpError(403,"PT403","account_unavailable")).destination);
    }

    @Test public void transportAndRpcFailuresStayOnRetry() {
        assertEquals(AdminRoleResolver.Destination.RETRY,AdminRoleResolver.interpret(false,
                new ErrorMapper.HttpError(405,"25006", "")).destination);
        assertEquals(AdminRoleResolver.Destination.RETRY,AdminRoleResolver.interpret(false,
                new ErrorMapper.HttpError(404,"PGRST202", "")).destination);
        assertEquals(AdminRoleResolver.Destination.RETRY,AdminRoleResolver.interpret(false,
                new java.io.IOException("network unavailable")).destination);
    }

    @Test public void expiredSessionRoutesToLoginAndMalformedDashboardRetries() {
        assertEquals(AdminRoleResolver.Destination.LOGIN,AdminRoleResolver.interpret(false,
                new ErrorMapper.HttpError(401,"401", "")).destination);
        assertEquals(AdminRoleResolver.Destination.RETRY,AdminRoleResolver.interpret(false,null).destination);
    }
}
