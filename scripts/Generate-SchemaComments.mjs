import fs from 'node:fs';
import crypto from 'node:crypto';

// 输入是只读结构快照和人工中文映射；本工具不连接数据库，也不执行生成的SQL。
const [snapshotPath, mappingPath, outputPath, reportPath] = process.argv.slice(2);
const chineseAll = process.argv[6] === '--chinese-all';
const hasChinese = value => /[\u3400-\u9fff]/u.test(value || '');
if (!snapshotPath || !mappingPath || !outputPath || !reportPath) throw new Error('用法：node Generate-SchemaComments.mjs 结构快照JSON 中文映射JSON 新SQL路径 新覆盖报告路径');
if (fs.existsSync(outputPath) || fs.existsSync(reportPath)) throw new Error('交付文件已存在，拒绝覆盖，请选择新的增量文件名');
const snapshot=JSON.parse(fs.readFileSync(snapshotPath,'utf8'));
const mapping=JSON.parse(fs.readFileSync(mappingPath,'utf8'));
if(snapshot.database!=='token')throw new Error('数据库守卫失败，只允许token');
const keys=['column_type','is_nullable','column_default','extra','character_set_name','collation_name','generation_expression'];
const hash=column=>crypto.createHash('sha256').update(keys.map(key=>column[key]??'<NULL>').join('\x1f'),'utf8').digest('hex');
const literal=value=>"'"+String(value).replaceAll("'","''")+"'";
const quoted=value=>{if(!/^[A-Za-z_][A-Za-z0-9_]*$/.test(value))throw new Error('不支持的标识符');return '`'+value+'`';};
const hex=value=>'0x'+Buffer.from(value,'utf8').toString('hex');
const sql=[];const report={database:'token',tableCount:snapshot.tables.length,columns:0,plannedTableComments:0,plannedColumnComments:0,preservedColumnComments:0,tables:[]};
function definitions(createSql) {
  const start=createSql.indexOf('(');if(start<0)throw new Error('无法解析SHOW CREATE TABLE');
  let depth=1,quote=null,begin=start+1;const result=new Map();
  for(let i=start+1;i<createSql.length;i++) {
    const c=createSql[i];
    if(quote){if(c==='\\'&&quote!=='`'&&!snapshot.sqlMode.split(',').includes('NO_BACKSLASH_ESCAPES')){i++;continue;}if(c===quote){if(createSql[i+1]===quote){i++;continue;}quote=null;}continue;}
    if(c==='\''||c==='"'||c==='`'){quote=c;continue;}
    if(c==='(')depth++;if(c===')')depth--;
    if((c===','&&depth===1)||depth===0){const item=createSql.slice(begin,i).trim();const match=item.match(/^`([A-Za-z_][A-Za-z0-9_]*)`\s+/);if(match)result.set(match[1],item);begin=i+1;if(depth===0)break;}
  }
  if(depth!==0||quote)throw new Error('字段定义括号或引号不完整，已拒绝生成');return result;
}
function withComment(definition,comment) {
  let quote=null;
  for(let i=0;i<definition.length;i++){
    const c=definition[i];
    if(quote){if(c==='\\'&&quote!=='`'&&!snapshot.sqlMode.split(',').includes('NO_BACKSLASH_ESCAPES')){i++;continue;}if(c===quote){if(definition[i+1]===quote){i++;continue;}quote=null;}continue;}
    if(c==='\''||c==='"'||c==='`'){quote=c;continue;}
    if((i===0||/\W/.test(definition[i-1]))&&/^COMMENT\s+'(?:[^'\\]|\\.|'')*'(?=\s|$)/i.test(definition.slice(i)))return definition.slice(0,i)+definition.slice(i).replace(/^COMMENT\s+'(?:[^'\\]|\\.|'')*'/i,'COMMENT '+literal(comment));
  }
  return definition+' COMMENT '+literal(comment);
}
const noBackslash=snapshot.sqlMode.split(',').includes('NO_BACKSLASH_ESCAPES')?1:0;
sql.push('-- 当前 Token 库表及字段中文注释维护。仅生成，执行需取得明确授权。',
  '-- 结构快照来自只读information_schema和SHOW CREATE TABLE；不读取或改写业务数据。',
  chineseAll
    ? '-- 保留有效中文说明；补缺和英文枚举，源码确认的歧义仅在旧注释匹配时澄清。字段结构漂移则跳过提示；DDL隐式提交，不宣称可以回滚。'
    : '-- 优先保留非空注释，字段结构漂移则跳过提示；DDL隐式提交，不宣称可以回滚。',
  'USE token;','SET NAMES utf8mb4;','DELIMITER $$','DROP PROCEDURE IF EXISTS token_schema_comments$$','CREATE PROCEDURE token_schema_comments()',
  'BEGIN','    DECLARE changed_count INT DEFAULT 0;','    DECLARE skipped_count INT DEFAULT 0;','    DECLARE kept_count INT DEFAULT 0;',
  '    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN RESIGNAL; END;',
  "    IF DATABASE() IS NULL OR DATABASE()<>'token' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='数据库守卫失败，只允许token'; END IF;",
  `    IF (FIND_IN_SET('NO_BACKSLASH_ESCAPES',@@SESSION.sql_mode)>0)<>${noBackslash} THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SQL引号模式与结构快照不同，请只读重新生成注释增量'; END IF;`);
for(const table of snapshot.tables){
  const tableName=table.name;const keepTable=Boolean(table.comment)&&(!chineseAll||hasChinese(table.comment));
  const tableComment=(keepTable?table.comment:null)||mapping.tables[tableName];if(!tableComment||(chineseAll&&!hasChinese(tableComment)))throw new Error('缺少表中文映射：'+tableName);
  const defs=definitions(table.createSql);const columnsReport=[];const tableWhere=`TABLE_SCHEMA='token' AND TABLE_NAME=${literal(tableName)}`;
  if(!keepTable){
    const alter=`ALTER TABLE ${quoted(tableName)} COMMENT=${literal(tableComment)}`;
    sql.push(`    -- 表注释：${tableName}`,`    IF EXISTS(SELECT 1 FROM information_schema.TABLES WHERE ${tableWhere} AND TABLE_TYPE='BASE TABLE' AND TABLE_COMMENT=${literal(table.comment||'')}) THEN`,
      "        IF DATABASE()<>'token' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='数据库守卫失败'; END IF;",
      `        SET @token_comment_sql=CONVERT(${hex(alter)} USING utf8mb4);`,
      '        PREPARE comment_stmt FROM @token_comment_sql; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;',
      '        SET changed_count=changed_count+1;','    ELSE SET kept_count=kept_count+1; END IF;');
    report.plannedTableComments++;
  }
  for(const column of table.columns){
    report.columns++;const name=column.column_name;const definition=defs.get(name);if(!definition)throw new Error('SHOW CREATE缺少完整字段定义：'+tableName+'.'+name);
    const replacement=mapping.replacements?.[tableName+'.'+name];
    if(replacement&&(!chineseAll||replacement.expected!==column.column_comment||!hasChinese(replacement.comment)||!replacement.evidence))throw new Error('注释澄清必须提供匹配的旧说明、中文新说明与源码证据：'+tableName+'.'+name);
    const keepColumn=!replacement&&Boolean(column.column_comment)&&(!chineseAll||hasChinese(column.column_comment));
    const comment=replacement?.comment||(keepColumn?column.column_comment:null)||mapping.overrides[tableName+'.'+name]||mapping.columns[name];if(!comment||(chineseAll&&!hasChinese(comment)))throw new Error('缺少字段中文映射：'+tableName+'.'+name);
    const item={name,comment,action:replacement?'按源码澄清历史说明':keepColumn?'保留已有注释':column.column_comment?'补充中文说明':'补空注释'};if(replacement)item.evidence=replacement.evidence;columnsReport.push(item);
    if(keepColumn){report.preservedColumnComments++;continue;}
    if(/password|secret|token_hash|credential_hash/i.test(name)&&column.column_default!==null&&column.column_default!=='')throw new Error('敏感字段存在非空默认值，拒绝把该定义写入交付：'+tableName+'.'+name);
    const expected=hash(column);item.structureHash=expected;
    const meta=keys.map(key=>`COALESCE(${key.toUpperCase()},'<NULL>')`).join(',');
    const where=`${tableWhere} AND COLUMN_NAME=${literal(name)}`;
    sql.push(`    -- 字段注释：${tableName}.${name}；保留SHOW CREATE完整定义。`,
      `    IF EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE ${where} AND COLUMN_COMMENT=${literal(column.column_comment||'')}) THEN`,
      `        IF EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE ${where} AND SHA2(CONVERT(CONCAT_WS(0x1f,${meta}) USING utf8mb4),256)=${literal(expected)})`,
      `           AND EXISTS(SELECT 1 FROM information_schema.TABLES WHERE ${tableWhere} AND TABLE_TYPE='BASE TABLE' AND TABLE_COLLATION=${literal(table.collation)}) THEN`,
      "            IF DATABASE()<>'token' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='数据库守卫失败'; END IF;",
      `            SET @token_comment_sql=CONVERT(${hex('ALTER TABLE '+quoted(tableName)+' MODIFY COLUMN '+withComment(definition,comment))} USING utf8mb4);`,
      '            PREPARE comment_stmt FROM @token_comment_sql; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;',
      '            SET changed_count=changed_count+1;',
      `        ELSE SET skipped_count=skipped_count+1; SELECT ${literal(tableName+'.'+name)} AS skipped_object,'结构已变化，保留原字段并跳过注释' AS reason; END IF;`,
      '    ELSE SET kept_count=kept_count+1; END IF;');
    report.plannedColumnComments++;
  }
  report.tables.push({name:tableName,comment:tableComment,action:table.comment?'保留已有注释':'补空注释',columns:columnsReport});
}
sql.push('    SET @token_comment_sql=NULL;',"    SELECT changed_count AS comments_added,kept_count AS comments_preserved,skipped_count AS schema_changes_skipped;",
  `    SELECT TABLE_NAME AS unreviewed_table,'本快照之外的表，未修改' AS reason FROM information_schema.TABLES WHERE TABLE_SCHEMA='token' AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME NOT IN (${snapshot.tables.map(table=>literal(table.name)).join(',')});`,
  'END$$','CALL token_schema_comments()$$','DROP PROCEDURE token_schema_comments$$','DELIMITER ;','');
let outputSql=sql.join('\n');
if(chineseAll){
  outputSql += "\n-- 手工执行后检查本库全部对象；无记录表示中文说明覆盖完整。\nSELECT TABLE_NAME,COLUMN_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='token' AND COLUMN_COMMENT NOT REGEXP '[一-龥]';\nSELECT TABLE_NAME,TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='token' AND TABLE_TYPE='BASE TABLE' AND TABLE_COMMENT NOT REGEXP '[一-龥]';\n";
}
fs.writeFileSync(outputPath,outputSql,{encoding:'utf8',flag:'wx'});
fs.writeFileSync(reportPath,JSON.stringify(report,null,2)+'\n',{encoding:'utf8',flag:'wx'});
console.log(JSON.stringify({tables:report.tableCount,columns:report.columns,tableComments:report.plannedTableComments,columnComments:report.plannedColumnComments,preserved:report.preservedColumnComments}));
