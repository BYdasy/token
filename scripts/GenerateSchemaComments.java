import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 只读结构审计与注释SQL生成器，不执行任何数据库写入；不启动Spring。 */
class GenerateSchemaComments {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args) {
        try {
            if(args.length!=3||!Set.of("inspect","coverage","missing").contains(args[0]))throw new IllegalArgumentException("用法：inspect、coverage或missing application.yml 输出JSON路径");
            boolean coverage=!"inspect".equals(args[0]);Map<String,Object> schema=inspect(Path.of(args[1]),args[0]);
            Path target=Path.of(args[2]);if(Files.exists(target))throw new IllegalArgumentException("输出已存在，拒绝覆盖");
            Files.writeString(target,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(schema),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            System.out.println(coverage?JSON.writeValueAsString(schema):"已只读导出表结构，数据库 token，表数量："+((List<?>)schema.get("tables")).size()+"；未读取业务记录，未执行DDL/DML。");
        } catch(SQLException exception){System.err.println("只读结构连接失败，SQLState="+exception.getSQLState()+"，错误码="+exception.getErrorCode());System.exit(2);}
        catch(Exception exception){System.err.println("结构处理失败："+(exception instanceof IllegalArgumentException?exception.getMessage():"请检查配置与输出文件权限"));System.exit(2);}
    }
    static Map<String,Object> inspect(Path config,String mode) throws Exception {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(20);
        Map<?,?> yaml=new Yaml(new SafeConstructor(options)).load(Files.readString(config));
        Map<?,?> datasource=(Map<?,?>)((Map<?,?>)yaml.get("spring")).get("datasource");
        String url=resolve(datasource.get("url"),"DB_URL"),username=resolve(datasource.get("username"),"DB_USERNAME"),password=resolve(datasource.get("password"),"DB_PASSWORD");
        if(!url.startsWith("jdbc:mysql://")||!"/token".equals(java.net.URI.create(url.substring(5)).getPath()))throw new IllegalArgumentException("只允许明确连接当前 token 数据库");
        DriverManager.setLoginTimeout(15);
        try(Connection connection=DriverManager.getConnection(url,username,password)) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try {
                try(Statement statement=connection.createStatement()) {
                    statement.setQueryTimeout(30);
                    try(ResultSet rows=statement.executeQuery("SELECT DATABASE()")){if(!rows.next()||!"token".equals(rows.getString(1)))throw new IllegalArgumentException("数据库守卫失败");}
                    if("missing".equals(mode)) {
                        List<Map<String,Object>> keys=new ArrayList<>();
                        try(ResultSet rows=statement.executeQuery("SELECT DISTINCT t.type,t.tkey FROM translates t WHERE t.type IN(1,5,7) AND NOT EXISTS(SELECT 1 FROM translates e WHERE e.type=t.type AND e.tkey=t.tkey AND LOWER(e.lang)='en' AND e.tvalue IS NOT NULL AND TRIM(e.tvalue)<>'') ORDER BY t.type,t.tkey")) {
                            while(rows.next())keys.add(Map.of("type",rows.getInt(1),"key",rows.getString(2)));
                        }
                        return Map.of("database","token","missingEnglishKeys",keys);
                    }
                    if("coverage".equals(mode)) {
                        List<Map<String,Object>> totals=new ArrayList<>();
                        try(ResultSet rows=statement.executeQuery("SELECT type,COUNT(DISTINCT tkey) total_keys,COUNT(DISTINCT CASE WHEN LOWER(lang)='en' AND tvalue IS NOT NULL AND TRIM(tvalue)<>'' THEN tkey END) english_keys FROM translates WHERE type IN(1,5,7) GROUP BY type ORDER BY type")) {
                            while(rows.next())totals.add(Map.of("type",rows.getInt(1),"totalKeys",rows.getLong(2),"englishKeys",rows.getLong(3)));
                        }
                        List<Integer> states=new ArrayList<>();try(ResultSet rows=statement.executeQuery("SELECT status FROM translate_language WHERE LOWER(lang)='en'")){while(rows.next())states.add(rows.getInt(1));}
                        String version;try(ResultSet rows=statement.executeQuery("SELECT VERSION()")){rows.next();version=rows.getString(1);}
                        return Map.of("database","token","serverVersion",version,"englishLanguageStates",states,"translationCoverage",totals);
                    }
                    List<Map<String,Object>> tables=new ArrayList<>();
                    try(ResultSet rows=statement.executeQuery("SELECT TABLE_NAME,TABLE_COMMENT,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='token' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
                        while(rows.next()){Map<String,Object> table=new LinkedHashMap<>();table.put("name",rows.getString(1));table.put("comment",rows.getString(2));table.put("collation",rows.getString(3));tables.add(table);}
                    }
                    for(Map<String,Object> table:tables) {
                        String name=(String)table.get("name");if(!name.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("存在未支持的表标识符，停止生成");
                        try(ResultSet rows=statement.executeQuery("SHOW CREATE TABLE `"+name+"`")){rows.next();table.put("createSql",rows.getString(2));}
                        List<Map<String,Object>> columns=new ArrayList<>();
                        try(PreparedStatement query=connection.prepareStatement("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME,GENERATION_EXPRESSION,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='token' AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
                            query.setString(1,name);query.setQueryTimeout(30);
                            try(ResultSet rows=query.executeQuery()){while(rows.next()){Map<String,Object> column=new LinkedHashMap<>();for(int i=1;i<=9;i++)column.put(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),rows.getString(i));columns.add(column);}}
                        }
                        table.put("columns",columns);
                    }
                    String sqlMode;try(ResultSet rows=statement.executeQuery("SELECT @@SESSION.sql_mode")){rows.next();sqlMode=rows.getString(1);}
                    return Map.of("database","token","sqlMode",sqlMode,"tables",tables);
                }
            } finally {connection.rollback();}
        }
    }
    private static String resolve(Object raw,String key){String environment=System.getenv(key);if(environment!=null)return environment;String value=Objects.toString(raw,"");if(value.startsWith("${"+key+":")&&value.endsWith("}"))return value.substring(key.length()+3,value.length()-1);if(value.startsWith("${"))throw new IllegalArgumentException("缺少数据库环境变量");return value;}
}
