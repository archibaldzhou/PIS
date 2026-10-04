package com.pis.api;

/** Bounded diagnostic structure only: no messages, SQL, request values or suppressed payloads. */
public final class SafeFailure {
    private SafeFailure() {}
    public static String describe(Throwable failure) {
        var output=new StringBuilder();
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(int depth=0;failure!=null&&depth<4&&seen.add(failure);depth++,failure=failure.getCause()) {
            output.append(depth==0?"":" <- ").append(failure.getClass().getName());
            int count=0;
            for(var frame:failure.getStackTrace()) {
                String type=frame.getClassName(),method=frame.getMethodName();
                if(count==6)break;
                if(!(type.startsWith("com.pis.")||type.startsWith("org.springframework.")||type.startsWith("com.zaxxer.hikari.")||type.startsWith("org.postgresql.")))continue;
                if(type.length()>160||method.length()>80||!type.matches("[A-Za-z0-9_.$]+")||!method.matches("[A-Za-z0-9_$<>]+"))continue;
                output.append(" at ").append(type).append('.').append(method).append(':').append(frame.getLineNumber());count++;
            }
        }
        return output.toString();
    }
}
