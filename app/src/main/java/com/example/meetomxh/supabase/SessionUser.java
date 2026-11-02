package com.example.meetomxh.supabase;
public final class SessionUser {
 private final String id,email;
 SessionUser(String id,String email){this.id=id;this.email=email;}
 public String getUid(){return id;}
 public String getEmail(){return email;}
 public String getDisplayName(){return "Meeto";}
}
