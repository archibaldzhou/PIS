import java.sql.*;
/** Test-only local schema controller. No credentials in arguments/output, no DROP or database rollback. */
class PgRehearsal {
 public static void main(String[] args)throws Exception {
  String url=System.getenv("PIS_TEST_DB_URL");
  if(url==null||!url.startsWith("jdbc:postgresql://127.0.0.1:")||!java.net.URI.create(url.substring(5)).getPath().endsWith("_test"))throw new IllegalArgumentException("LOCAL_TEST_DATABASE_REQUIRED");
  String schema=args[1];if(!schema.matches("pis_deploy_[a-f0-9]{32}"))throw new IllegalArgumentException("OWNED_SCHEMA_REQUIRED");
  try(var c=DriverManager.getConnection(url,System.getenv("PIS_TEST_DB_USERNAME"),System.getenv("PIS_TEST_DB_PASSWORD"));var st=c.createStatement()) {
   st.setQueryTimeout(5);if(c.getMetaData().getDatabaseMajorVersion()!=17)throw new IllegalArgumentException("PG17_REQUIRED");
   if(args[0].equals("create")){st.execute("CREATE SCHEMA "+schema);System.out.println("CREATED");}
   else if(args[0].equals("state")) {
    try(var r=st.executeQuery("SELECT version||':'||checksum FROM "+schema+".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")){while(r.next())System.out.println(r.getString(1));}
    try(var r=st.executeQuery("SELECT (SELECT count(*) FROM "+schema+".app_user)||':'||(SELECT count(*) FROM "+schema+".audit_event)||':'||(SELECT count(*) FROM "+schema+".storage_version)")){r.next();System.out.println("COUNTS:"+r.getString(1));}
   } else throw new IllegalArgumentException("UNSUPPORTED_ACTION");
  }
 }
}
