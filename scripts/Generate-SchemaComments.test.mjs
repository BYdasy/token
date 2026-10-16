import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// 仅测试离线生成器和DDL文本属性保留；不会连接数据库或执行生成的DDL。
function generate(columns,definitions,options={}) {
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'token-schema-test-'));
  const snapshot={database:'token',sqlMode:'STRICT_TRANS_TABLES',tables:[{name:'test_table',comment:'',collation:'utf8mb4_unicode_ci',createSql:'CREATE TABLE `test_table` (\n'+definitions.join(',\n')+'\n) ENGINE=InnoDB',columns}]};
  const snapshotPath=path.join(directory,'snapshot.json'),mapPath=path.join(directory,'mapping.json'),sqlPath=path.join(directory,'out.sql'),reportPath=path.join(directory,'report.json');
  fs.writeFileSync(snapshotPath,JSON.stringify(snapshot));
  fs.writeFileSync(mapPath,JSON.stringify({tables:{test_table:'测试表'},columns:options.mapping??Object.fromEntries(columns.map(column=>[column.column_name,'测试字段'])),overrides:{},replacements:options.replacements}));
  const run=spawnSync(process.execPath,['scripts/Generate-SchemaComments.mjs',snapshotPath,mapPath,sqlPath,reportPath,...(options.chineseAll?['--chinese-all']:[])],{encoding:'utf8'});
  const sql=fs.existsSync(sqlPath)?fs.readFileSync(sqlPath,'utf8'):'';
  return {run,sql,sqlPath,reportPath,snapshotPath,mapPath,statements:[...sql.matchAll(/CONVERT\(0x([0-9a-f]+) USING utf8mb4\)/g)].map(match=>Buffer.from(match[1],'hex').toString('utf8'))};
}
const column=(name,type,extra='',comment='')=>({column_name:name,column_type:type,is_nullable:'YES',column_default:null,extra,character_set_name:null,collation_name:null,generation_expression:'',column_comment:comment});
test('保留自增类型、精度、当前时间更新、枚举逗号和生成列定义',()=>{
  const columns=[column('id','bigint unsigned','auto_increment'),column('updated_at','datetime(6)','DEFAULT_GENERATED on update CURRENT_TIMESTAMP(6)'),column('choice',"enum('a,b','c')"),{...column('generated','int','STORED GENERATED'),generation_expression:'(length(`choice`))'}];
  const definitions=['`id` bigint unsigned NOT NULL AUTO_INCREMENT','`updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)',"`choice` enum('a,b','c') CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'a,b'",'`generated` int GENERATED ALWAYS AS ((length(`choice`))) STORED'];
  const result=generate(columns,definitions);assert.equal(result.run.status,0,result.run.stderr);
  for(const definition of definitions)assert.ok(result.statements.includes('ALTER TABLE `test_table` MODIFY COLUMN '+definition+" COMMENT '测试字段'"));
  assert.match(result.sql,/GENERATION_EXPRESSION/);assert.match(result.sql,/TABLE_COLLATION=/);assert.match(result.sql,/NO_BACKSLASH_ESCAPES/);
});
test('保留非空已有注释，明确空注释可补齐',()=>{
  const result=generate([column('existing','varchar(20)','','用户注释'),column('empty','varchar(20)')],["`existing` varchar(20) DEFAULT NULL COMMENT '用户注释'","`empty` varchar(20) DEFAULT NULL COMMENT ''"]);
  assert.equal(result.run.status,0,result.run.stderr);assert.equal(result.statements.filter(sql=>sql.includes('MODIFY COLUMN')).length,1);assert.ok(result.statements.some(sql=>sql.endsWith("COMMENT '测试字段'")));
});
test('未知中文映射和敏感非空默认值明确拒绝且不生成部分SQL',()=>{
  const unknown=generate([column('unknown','int')],['`unknown` int DEFAULT NULL'],{mapping:{}});assert.notEqual(unknown.run.status,0);assert.equal(unknown.sql,'');
  const sensitive=generate([{...column('password_hash','varchar(100)'),column_default:'禁止写出'}],["`password_hash` varchar(100) DEFAULT '禁止写出'"]);assert.notEqual(sensitive.run.status,0);assert.equal(sensitive.sql,'');
});
test('生成器拒绝覆盖已交付文件',()=>{
  const result=generate([column('id','int')],['`id` int DEFAULT NULL']);assert.equal(result.run.status,0,result.run.stderr);
  const again=spawnSync(process.execPath,['scripts/Generate-SchemaComments.mjs',result.snapshotPath,result.mapPath,result.sqlPath,result.reportPath],{encoding:'utf8'});assert.notEqual(again.status,0);assert.match(again.stderr,/拒绝覆盖/);
});
test('中文覆盖模式补纯英文枚举说明且保留完整定义与中文人工注释',()=>{
  const result=generate([column('state','varchar(30)','','PENDING/DONE'),column('custom','int','','人工中文说明')],
    ["`state` varchar(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE'","`custom` int COMMENT '人工中文说明'"],{chineseAll:true});
  assert.equal(result.run.status,0,result.run.stderr);
  assert.ok(result.statements.includes("ALTER TABLE `test_table` MODIFY COLUMN `state` varchar(30) NOT NULL DEFAULT 'PENDING' COMMENT '测试字段'"));
  assert.equal(result.statements.filter(sql=>sql.includes('MODIFY COLUMN')).length,1);
  assert.match(result.sql,/COLUMN_COMMENT='PENDING\/DONE'/);
  assert.match(result.sql,/token_schema_comments/);
});
test('源码澄清只接受匹配的旧注释且保持完整字段属性',()=>{
  const columns=[column('email','int','','电子邮箱')],definitions=["`email` int DEFAULT 0 COMMENT '电子邮箱'"];
  const replacement={expected:'电子邮箱',comment:'历史邮件限额整数，非邮箱地址',evidence:'参考数字表单'};
  const result=generate(columns,definitions,{chineseAll:true,replacements:{'test_table.email':replacement}});
  assert.equal(result.run.status,0,result.run.stderr);
  assert.ok(result.statements.includes("ALTER TABLE `test_table` MODIFY COLUMN `email` int DEFAULT 0 COMMENT '历史邮件限额整数，非邮箱地址'"));
  assert.match(result.sql,/COLUMN_COMMENT='电子邮箱'/);
  const drift=generate(columns,definitions,{chineseAll:true,replacements:{'test_table.email':{...replacement,expected:'其他旧说明'}}});
  assert.notEqual(drift.run.status,0);assert.equal(drift.sql,'');
});
