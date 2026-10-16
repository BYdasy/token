package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.model.query.MemberScope;
import java.util.*;

/** 数据库范围条件工厂；输入由服务实时授权，不查询或缓存任何身份权限。 */
public final class MemberScopeQuery {
    private MemberScopeQuery() { }
    public static String memberFilter(String column,MemberScope scope,List<Object> args) {
        return memberCondition(column,scope.global(),!scope.roots().isEmpty(),scope.roots(),args);
    }
    public static String ownerFilter(String owner,MemberScope scope,List<Object> args,boolean includeGlobal) {
        return ownerCondition(owner,scope.global(),!scope.roots().isEmpty(),scope.roots(),args,includeGlobal);
    }
    public static String descendantCondition(String column,Collection<Long> roots,List<Object> args) {
        return descendantCondition(column,roots,args,0,200,true);
    }
    public static String descendantCondition(String column,Collection<Long> roots,List<Object> args,int exact,int maximum,boolean includeSelf){
        args.addAll(roots);
        return descendant(column,Collections.nCopies(roots.size(),"?"),exact,maximum,includeSelf);
    }
    /** 注解Provider复用同一递归语义，集合值仍由MyBatis逐项绑定。 */
    public static String boundDescendants(String column,Collection<?> roots,String parameter){
        identifier(parameter);
        List<String> values=new ArrayList<>();
        for(int index=0;index<roots.size();index++)values.add("#{"+parameter+"["+index+"]}");
        return descendant(column,values,0,200,true);
    }
    static String descendant(String column,List<String> values,int exact,int maximum,boolean includeSelf){
        identifier(column);
        if(values.isEmpty())return " AND 1=0";
        if(values.size()>100)throw new BusinessException("server.compute.relation.tooManyRoots");
        return " AND "+column+" IN (WITH RECURSIVE member_tree(id,depth,visited) AS ("
                +"SELECT id,0,CAST(CONCAT('/',id,'/') AS CHAR(6000)) FROM compute_member WHERE id IN ("+String.join(",",values)+") "
                +"UNION ALL SELECT c.id,p.depth+1,CONCAT(TRIM(p.visited),c.id,'/') FROM compute_member c JOIN member_tree p ON c.inviter_id=p.id "
                +"WHERE p.depth<"+maximum+" AND LOCATE(CONCAT('/',c.id,'/'),p.visited)=0) SELECT id FROM member_tree WHERE depth"
                +(exact>0?"="+exact:includeSelf?">=0":">0")+")";
    }
    public static String physicalRootCondition(String column,String rootWhere,List<Object> rootArgs,List<Object> args){
        identifier(column);args.addAll(rootArgs);
        return " AND "+column+" IN (WITH RECURSIVE physical_tree(id,depth) AS (SELECT id,0 FROM compute_member"+rootWhere
                +" UNION ALL SELECT c.id,p.depth+1 FROM compute_member c JOIN physical_tree p ON c.inviter_id=p.id WHERE p.depth<200) SELECT id FROM physical_tree)";
    }
    public static String ownerCondition(String owner,boolean global,boolean enabled,List<Long> roots,List<Object> args,boolean includeGlobal){
        identifier(owner);
        if(global)return "";
        if(!enabled)return " AND 1=0";
        String own=descendantCondition(owner,roots,args,0,200,true);
        return " AND ("+(includeGlobal?owner+" IS NULL OR ":"")+"("+owner+" IS NOT NULL"+own+"))";
    }
    public static String memberCondition(String column,boolean global,boolean enabled,List<Long> roots,List<Object> args){
        if(global)return "";
        if(!enabled)return " AND 1=0";
        return descendantCondition(column,roots,args,0,200,true);
    }
    public static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static void identifier(String value){if(value==null||!value.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?"))throw new IllegalArgumentException("关系查询列必须由服务端指定");}
}
