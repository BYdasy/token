import { createRequire } from 'node:module';
import path from 'node:path';

// 使用项目已有 TypeScript 解析器读取声明，不执行应用代码或动态表达式。
const require = createRequire(new URL('../token-user/package.json', import.meta.url));
const ts = require('typescript');
const vueRequire = createRequire(require.resolve('vue/package.json'));
const { baseParse } = vueRequire('@vue/compiler-dom');
const keyPattern = /^(server|admin|user)\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/;
const literal = node => node && (ts.isStringLiteralLike(node) || ts.isNumericLiteral(node)) ? node.text : undefined;
const unique = values => [...new Set(values.filter(value => typeof value === 'string'))];

/** 有限声明生成真实键，即使数据库及所有字典均从未包含该键。 */
export function finiteTranslationReferences(sources) {
  const references = [], unresolved = [];
  const sourceMap = new Map(sources.map(source => [source.file, source.text]));
  const add = (key, file, kind, expression) => {
    if (keyPattern.test(key)) references.push({ key, file, kind, expression });
  };
  for (const { file, text } of sources.filter(source => /\.(?:ts|tsx|js|vue)$/.test(source.file))) {
    const code = file.endsWith('.vue') ? [...text.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]).join('\n') : text;
    const ast = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    const declarations = new Map(), scopedDeclarations = new Map(), functions = new Map(), importedDeclarations = new Map();
    const walk = (node, visit) => { visit(node); ts.forEachChild(node, child => walk(child, visit)); };
    const bodyOf = node => {
      if (!node.body) return undefined;
      return ts.isBlock(node.body) ? node.body.statements.find(ts.isReturnStatement)?.expression : node.body;
    };
    walk(ast, node => {
      if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
        let scope = node.parent;
        while (scope && !(ts.isFunctionDeclaration(scope) || ts.isArrowFunction(scope) || ts.isFunctionExpression(scope))) scope = scope.parent;
        if (scope) {
          if (!scopedDeclarations.has(scope)) scopedDeclarations.set(scope, new Map());
          scopedDeclarations.get(scope).set(node.name.text, node.initializer);
        } else declarations.set(node.name.text, node.initializer);
        if (ts.isArrowFunction(node.initializer) || ts.isFunctionExpression(node.initializer)) functions.set(node.name.text, node.initializer);
      }
      if (ts.isFunctionDeclaration(node) && node.name) functions.set(node.name.text, node);
    });
    function initializer(node) {
      for (let scope = node.parent; scope; scope = scope.parent) {
        if (scopedDeclarations.get(scope)?.has(node.text)) return scopedDeclarations.get(scope).get(node.text);
        if ((ts.isFunctionDeclaration(scope) || ts.isArrowFunction(scope) || ts.isFunctionExpression(scope)) && scope.parameters.some(param => ts.isIdentifier(param.name) && param.name.text === node.text)) return undefined;
      }
      return importedDeclarations.get(node.getSourceFile()?.fileName)?.get(node.text) ?? declarations.get(node.text);
    }
    // 只加载本次源码清单内的静态导出常量；不会执行模块或读取 node_modules。
    for (const statement of ast.statements) {
      if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier)) continue;
      const specifier = statement.moduleSpecifier.text;
      const base = specifier.startsWith('#/') ? 'token-admin/apps/web-ele/src/' + specifier.slice(2)
        : specifier.startsWith('@/') ? (file.startsWith('token-user/') ? 'token-user/src/' : 'token-admin/apps/web-ele/src/') + specifier.slice(2)
          : specifier.startsWith('.') ? path.posix.normalize(path.posix.join(path.posix.dirname(file), specifier)) : '';
      const resolved = [base, base + '.ts', base + '.js', base + '/index.ts'].find(candidate => sourceMap.has(candidate));
      const bindings = statement.importClause?.namedBindings;
      if (!resolved || !bindings || !ts.isNamedImports(bindings)) continue;
      const imported = ts.createSourceFile(resolved, sourceMap.get(resolved), ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
      const constants = new Map();
      for (const declaration of imported.statements.filter(ts.isVariableStatement).flatMap(value => [...value.declarationList.declarations]))
        if (ts.isIdentifier(declaration.name) && declaration.initializer) constants.set(declaration.name.text, declaration.initializer);
      importedDeclarations.set(resolved, constants);
      for (const element of bindings.elements) if (constants.has((element.propertyName ?? element.name).text))
        declarations.set(element.name.text, constants.get((element.propertyName ?? element.name).text));
    }
    function objects(node, depth = 0) {
      if (!node || depth > 10) return [];
      if (ts.isIdentifier(node)) return objects(initializer(node), depth + 1);
      if (ts.isObjectLiteralExpression(node)) return [node];
      if (ts.isArrayLiteralExpression(node)) return node.elements.flatMap(element => objects(ts.isSpreadElement(element) ? element.expression : element, depth + 1));
      if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isSatisfiesExpression(node)) return objects(node.expression, depth + 1);
      if (ts.isConditionalExpression(node)) return [...objects(node.whenTrue, depth + 1), ...objects(node.whenFalse, depth + 1)];
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && ['computed', 'ref', 'reactive', 'shallowRef'].includes(node.expression.text)) {
        const argument = node.arguments[0];
        return objects(argument && (ts.isArrowFunction(argument) || ts.isFunctionExpression(argument)) ? bodyOf(argument) : argument, depth + 1);
      }
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && ['filter', 'slice'].includes(node.expression.name.text)) return objects(node.expression.expression, depth + 1);
      return [];
    }
    function bindCollection(env, name, collection) {
      env.set(name, values(collection, env));
      for (const object of objects(collection)) for (const property of object.properties) if (ts.isPropertyAssignment(property)) {
        const key = `${name}.${property.name.getText().replace(/^['"]|['"]$/g, '')}`;
        env.set(key, unique([...(env.get(key) ?? []), ...values(property.initializer)]));
      }
    }
    function collectionItems(node, depth = 0) {
      if (!node || depth > 10) return [];
      if (ts.isIdentifier(node)) return collectionItems(initializer(node), depth + 1);
      if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isSatisfiesExpression(node)) return collectionItems(node.expression, depth + 1);
      if (ts.isArrayLiteralExpression(node)) return [...node.elements];
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && ['computed', 'ref', 'reactive', 'shallowRef'].includes(node.expression.text)) {
        const argument = node.arguments[0];
        return collectionItems(argument && (ts.isArrowFunction(argument) || ts.isFunctionExpression(argument)) ? bodyOf(argument) : argument, depth + 1);
      }
      return [];
    }
    function bindPattern(env, pattern, collection) {
      if (ts.isIdentifier(pattern)) return bindCollection(env, pattern.text, collection);
      if (ts.isArrayBindingPattern(pattern)) pattern.elements.forEach((binding, index) => {
        if (!ts.isBindingElement(binding) || !ts.isIdentifier(binding.name)) return;
        env.set(binding.name.text, unique(collectionItems(collection).flatMap(row => values(collectionItems(row)[index], env))));
      });
    }
    let valueTrace;
    const unknownValue = () => { if (valueTrace) valueTrace.unknown = true; return []; };
    function values(node, env = new Map(), depth = 0) {
      if (!node || depth > 12) return unknownValue();
      if (env.has(node.getText())) {
        const bound = env.get(node.getText());
        return bound.length || env.has(`?absent:${node.getText()}`) ? bound : unknownValue();
      }
      const value = literal(node);
      if (value !== undefined) return [value];
      if (node.kind === ts.SyntaxKind.NullKeyword || ts.isIdentifier(node) && node.text === 'undefined') return [];
      if (ts.isIdentifier(node)) return env.has(node.text) ? env.get(node.text).length ? env.get(node.text) : unknownValue() : values(initializer(node), env, depth + 1);
      if (ts.isPropertyAccessExpression(node)) {
        if (env.has(node.getText())) return env.get(node.getText());
        if (node.name.text === 'value') return values(node.expression, env, depth + 1);
        const rows = objects(node.expression);
        if (!rows.length) return unknownValue();
        return unique(rows.flatMap(object => object.properties.flatMap(property => ts.isPropertyAssignment(property) && property.name.getText().replace(/^['"]|['"]$/g, '') === node.name.text ? values(property.initializer, env, depth + 1) : [])));
      }
      if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isNonNullExpression(node) || ts.isSatisfiesExpression(node)) return values(node.expression, env, depth + 1);
      if (ts.isConditionalExpression(node)) {
        const trace = valueTrace;
        valueTrace = undefined;
        const yes = condition(node.condition, env);
        valueTrace = trace;
        return unique([...(yes !== false ? values(node.whenTrue, env, depth + 1) : []), ...(yes !== true ? values(node.whenFalse, env, depth + 1) : [])]);
      }
      if (ts.isArrayLiteralExpression(node)) return unique(node.elements.flatMap(element => values(ts.isSpreadElement(element) ? element.expression : element, env, depth + 1)));
      if (ts.isElementAccessExpression(node)) {
        const rows = objects(node.expression);
        if (rows.length) {
          // 一个闭合字面对象的动态索引只可能得到已声明值或 undefined；不能因索引来自 API 而漏掉映射值。
          const trace = valueTrace;
          valueTrace = undefined;
          const keys = values(node.argumentExpression, env, depth + 1);
          valueTrace = trace;
          const found = rows.flatMap(object => object.properties.flatMap(property => ts.isPropertyAssignment(property) && (!keys.length || keys.includes(property.name.getText().replace(/^['"]|['"]$/g, ''))) ? values(property.initializer, env, depth + 1) : []));
          return unique([...found, ...(!keys.length || !found.length ? [''] : [])]);
        }
      }
      if (ts.isBinaryExpression(node)) {
        const left = values(node.left, env, depth + 1);
        if (node.operatorToken.kind === ts.SyntaxKind.BarBarToken || node.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken)
          return unique([...left.filter(Boolean), ...(!left.length || left.includes('') ? values(node.right, env, depth + 1) : [])]);
        const right = values(node.right, env, depth + 1);
        if (node.operatorToken.kind === ts.SyntaxKind.PlusToken) return left.flatMap(a => right.map(b => a + b)).slice(0, 2000);
      }
      if (ts.isTemplateExpression(node)) {
        let result = [node.head.text];
        for (const span of node.templateSpans) result = result.flatMap(prefix => values(span.expression, env, depth + 1).map(value => prefix + value + span.literal.text)).slice(0, 2000);
        return result;
      }
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && ['toLowerCase', 'toUpperCase'].includes(node.expression.name.text))
        return values(node.expression.expression, env, depth + 1).map(value => node.expression.name.text === 'toLowerCase' ? value.toLowerCase() : value.toUpperCase());
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && ['computed', 'ref', 'reactive', 'shallowRef', 'String'].includes(node.expression.text)) {
        const argument = node.arguments[0];
        return values(argument && (ts.isArrowFunction(argument) || ts.isFunctionExpression(argument)) ? bodyOf(argument) : argument, env, depth + 1);
      }
      if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'Set') return values(node.arguments?.[0], env, depth + 1);
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && ['filter', 'slice'].includes(node.expression.name.text)) return values(node.expression.expression, env, depth + 1);
      return unknownValue();
    }
    function condition(node, env) {
      if (!node) return undefined;
      if (ts.isParenthesizedExpression(node)) return condition(node.expression, env);
      if (ts.isPrefixUnaryExpression(node) && node.operator === ts.SyntaxKind.ExclamationToken) {
        const value = condition(node.operand, env); return value === undefined ? undefined : !value;
      }
      if (ts.isBinaryExpression(node)) {
        const kind = node.operatorToken.kind;
        if ([ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.EqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsToken].includes(kind)) {
          const left = values(node.left, env), right = values(node.right, env);
          if (left.length === 1 && right.length === 1) return [ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.EqualsEqualsToken].includes(kind) ? left[0] === right[0] : left[0] !== right[0];
        }
        const a = condition(node.left, env), b = condition(node.right, env);
        if (kind === ts.SyntaxKind.BarBarToken) return a === true || b === true ? true : a === false && b === false ? false : undefined;
        if (kind === ts.SyntaxKind.AmpersandAmpersandToken) return a === false || b === false ? false : a === true && b === true ? true : undefined;
      }
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && node.expression.name.text === 'includes') {
        const haystack = values(node.expression.expression, env), needle = values(node.arguments[0], env);
        if (haystack.length && needle.length === 1) return haystack.includes(needle[0]);
      }
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && ['startsWith', 'endsWith'].includes(node.expression.name.text)) {
        const source = values(node.expression.expression, env), part = values(node.arguments[0], env);
        if (source.length === 1 && part.length === 1) return source[0][node.expression.name.text](part[0]);
      }
      if (ts.isElementAccessExpression(node) && ts.isIdentifier(node.expression) && ts.isObjectLiteralExpression(initializer(node.expression) ?? {}))
        return values(node, env).length > 0;
      return undefined;
    }
    function guardBindings(node, env) {
      if (ts.isParenthesizedExpression(node)) return guardBindings(node.expression, env);
      if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken) {
        guardBindings(node.left, env); guardBindings(node.right, env); return;
      }
      if (!ts.isCallExpression(node) || !ts.isPropertyAccessExpression(node.expression) || !['includes', 'has'].includes(node.expression.name.text)) return;
      const candidates = values(node.expression.expression, env), argument = node.arguments[0];
      if (!candidates.length || !argument) return;
      env.set(argument.getText(), candidates);
      if (ts.isCallExpression(argument) && ts.isIdentifier(argument.expression) && argument.expression.text === 'String' && argument.arguments[0]) env.set(argument.arguments[0].getText(), candidates);
    }
    function environment(node) {
      const env = new Map();
      const parents = [];
      for (let parent = node.parent; parent; parent = parent.parent) parents.unshift(parent);
      for (const parent of parents) {
        if ((ts.isArrowFunction(parent) || ts.isFunctionExpression(parent)) && ts.isCallExpression(parent.parent)
          && ts.isPropertyAccessExpression(parent.parent.expression) && ['map', 'flatMap', 'forEach'].includes(parent.parent.expression.name.text)) {
          const param = parent.parameters[0]?.name;
          if (param) bindPattern(env, param, parent.parent.expression.expression);
        }
        if (ts.isConditionalExpression(parent) && node.pos >= parent.whenTrue.pos && node.end <= parent.whenTrue.end) guardBindings(parent.condition, env);
        if (ts.isIfStatement(parent) && node.pos >= parent.thenStatement.pos && node.end <= parent.thenStatement.end) guardBindings(parent.expression, env);
      }
      return env;
    }
    function fields(node, env = new Map(), depth = 0) {
      if (!node || depth > 8) return null;
      if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isSatisfiesExpression(node)) return fields(node.expression, env, depth + 1);
      if (ts.isIdentifier(node)) return fields(initializer(node), env, depth + 1);
      if (ts.isConditionalExpression(node)) {
        const yes = condition(node.condition, env);
        if (yes !== undefined) return fields(yes ? node.whenTrue : node.whenFalse, env, depth + 1);
        const branches = [fields(node.whenTrue, env, depth + 1), fields(node.whenFalse, env, depth + 1)];
        const merged = new Map();
        merged.optionalKeys = new Set();
        for (const key of new Set(branches.flatMap(branch => [...(branch?.keys() ?? [])]))) {
          merged.set(key, unique(branches.flatMap(branch => branch?.get(key) ?? [])));
          if (branches.some(branch => !branch?.has(key) || branch.optionalKeys?.has(key))) merged.optionalKeys.add(key);
        }
        return merged;
      }
      if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken) {
        const yes = condition(node.left, env), result = yes === false ? new Map() : fields(node.right, env, depth + 1);
        if (result && yes === undefined) result.optionalKeys = new Set(result.keys());
        return result;
      }
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
        const fn = functions.get(node.expression.text);
        if (!fn) return null;
        const bound = new Map(env);
        fn.parameters.forEach((param, index) => {
          if (!ts.isIdentifier(param.name)) return;
          const argument = node.arguments[index] ?? param.initializer;
          bound.set(param.name.text, argument ? values(argument, env) : []);
          if (!argument) bound.set(`?absent:${param.name.text}`, ['true']);
        });
        return fields(bodyOf(fn), bound, depth + 1);
      }
      if (!ts.isObjectLiteralExpression(node)) return null;
      const result = new Map();
      result.optionalKeys = new Set();
      const fieldValue = (key, expression) => {
        const priorTrace = valueTrace;
        valueTrace = { unknown: false };
        const resolved = values(expression, env);
        result.delete(`?${key}`);
        if (valueTrace.unknown) result.set(`?${key}`, ['unknown']);
        valueTrace = priorTrace;
        result.set(key, resolved);
        result.optionalKeys.delete(key);
        result.optionalKeys.delete(`?${key}`);
      };
      for (const property of node.properties) {
        if (ts.isSpreadAssignment(property)) {
          const spread = fields(property.expression, env, depth + 1);
          for (const [key, value] of spread ?? []) {
            if (spread.optionalKeys?.has(key)) {
              if (!result.has(key)) result.optionalKeys.add(key);
              result.set(key, unique([...(result.get(key) ?? []), ...value]));
            } else {
              result.set(key, value);
              result.optionalKeys.delete(key);
              // 后面的确定字段会覆盖之前未知属性，不能留下已被覆盖的待核对标记。
              if (!key.startsWith('?') && !spread.has(`?${key}`)) result.delete(`?${key}`);
            }
          }
        }
        else if (ts.isPropertyAssignment(property)) fieldValue(property.name.getText().replace(/^['"]|['"]$/g, ''), property.initializer);
        else if (ts.isShorthandPropertyAssignment(property)) fieldValue(property.name.text, property.name);
      }
      return result;
    }
    function emitField(field) {
      if (!field || !field.get('key')?.length || field.has('label') && !field.optionalKeys?.has('label') || field.has('name') && !field.optionalKeys?.has('name')) return;
      const emit = (property, prefix) => (field.get(property) ?? []).forEach(value => add(prefix + value, file, '有限表单声明', `${property}: ${value}`));
      if (field.get('labelKey')?.length) emit('labelKey', 'admin.compute.');
      if (!field.get('labelKey')?.length || field.optionalKeys?.has('labelKey')) for (const key of field.get('key')) add(`admin.compute.${key === 'startsAt' ? 'common.rangeStart' : `field.${key}`}`, file, '有限表单字段', key);
      emit('hint', 'admin.compute.hint.');
      emit('group', 'admin.compute.group.');
      emit('validationKey', 'admin.compute.');
      // enumLabel 另有类型专用映射，交由真实函数调用解析，不能将所有选项硬拼 enum.*。
      // 未提供的可选参数不是动态引用；unknown 字段单独在 fields 中标记。
      for (const property of ['hint', 'labelKey', 'group', 'validationKey']) if (field.get(`?${property}`)?.length)
        unresolved.push({ file, kind: '表单属性待核对', expression: `${field.get('key').join('|')}.${property}` });
    }
    // 只解析实际使用 ComputeField / BusinessSearchFields 的模块，不能把业务内容 titleKey/bodyKey 当界面翻译。
    const searchBindings = [...text.matchAll(/<(?:BusinessForm|ComputeForm|BusinessSearchFields)\b[^>]*?:fields="(\w+)"/g)].map(match => match[1]);
    if (file.startsWith('token-admin/apps/web-ele/src/') && /\bComputeField\b|\bBusinessSearchFields\b|\bComputeForm\b|\bBusinessForm\b/.test(text)) {
      walk(ast, node => {
        if (!(ts.isObjectLiteralExpression(node) || ts.isCallExpression(node)) || ts.isSpreadAssignment(node.parent)) return;
        if (!/\bComputeField\b/.test(text)) {
          let inside = false;
          for (let parent = node; parent; parent = parent.parent) if (ts.isVariableDeclaration(parent) && searchBindings.includes(parent.name.getText(ast))) inside = true;
          if (!inside) return;
        }
        emitField(fields(node, environment(node)));
      });
    }
    const wrappers = new Map([['t', ''], ['$t', '']]);
    if (/\buseCompute\b/.test(text)) wrappers.set('c', 'admin.compute.');
    // label(key) => c(`merchant.field.${key}`) 等二次包装器按固定点展开。
    for (let pass = 0; pass < 5; pass++) for (const [name, fn] of functions) {
      const body = bodyOf(fn), param = fn.parameters[0]?.name;
      if (!fn.body || ts.isBlock(fn.body) && (fn.body.statements.length !== 1 || !ts.isReturnStatement(fn.body.statements[0]))) continue;
      if (!body || !param || !ts.isIdentifier(param) || !ts.isCallExpression(body) || !ts.isIdentifier(body.expression) || !wrappers.has(body.expression.text)) continue;
      const expression = body.arguments[0];
      if (ts.isTemplateExpression(expression) && expression.templateSpans.length === 1 && expression.templateSpans[0].expression.getText(ast) === param.text && !expression.templateSpans[0].literal.text)
        wrappers.set(name, wrappers.get(body.expression.text) + expression.head.text);
      if (ts.isBinaryExpression(expression) && expression.operatorToken.kind === ts.SyntaxKind.PlusToken && expression.right.getText(ast) === param.text && literal(expression.left) !== undefined)
        wrappers.set(name, wrappers.get(body.expression.text) + literal(expression.left));
    }
    function calls(tree, env = new Map(), origin = '有限翻译表达式', depth = 0) {
      if (depth > 4) return;
      walk(tree, node => {
        if (!ts.isCallExpression(node) || !ts.isIdentifier(node.expression)) return;
        const local = new Map([...environment(node), ...env]);
        const contexts = [...local].reduce((rows, [name, variants]) => variants.length ? rows.flatMap(row => variants.map(value => new Map([...row, [name, [value]]]))) : rows, [new Map()]);
        const reachable = context => {
          for (let parent = node.parent; parent; parent = parent.parent) if (ts.isConditionalExpression(parent) || ts.isIfStatement(parent)) {
            const yes = condition(ts.isConditionalExpression(parent) ? parent.condition : parent.expression, context);
            const whenTrue = parent.whenTrue ?? parent.thenStatement, whenFalse = parent.whenFalse ?? parent.elseStatement;
            if (yes === false && node.pos >= whenTrue.pos && node.end <= whenTrue.end || yes === true && whenFalse && node.pos >= whenFalse.pos && node.end <= whenFalse.end) return false;
          }
          return true;
        };
        if (wrappers.has(node.expression.text)) {
          const prefix = wrappers.get(node.expression.text), arg = node.arguments[0];
          for (const context of contexts.filter(reachable).slice(0, 2000)) {
            valueTrace = { unknown: false };
            const resolved = values(arg, context);
            const partial = valueTrace.unknown;
            valueTrace = undefined;
            for (const value of resolved) add(prefix + value, file, origin, arg?.getText());
            if ((!resolved.length || partial) && arg) unresolved.push({ file, kind: resolved.length ? '部分动态翻译参数待核对' : '动态翻译参数待核对', expression: `${prefix} + (${arg.getText()})` });
          }
        } else {
          const fn = functions.get(node.expression.text);
          if (!fn || !/(?:\bc|\bt|\$t)\(/.test(fn.body?.getText(ast) ?? '')) return;
          for (const context of contexts.slice(0, 2000)) {
            const bound = new Map(context);
            fn.parameters.forEach((param, index) => { if (ts.isIdentifier(param.name)) bound.set(param.name.text, values(node.arguments[index] ?? param.initializer, context)); });
            function statements(statement) {
              if (!statement) return false;
              if (ts.isBlock(statement)) { for (const child of statement.statements) if (statements(child)) return true; return false; }
              if (ts.isIfStatement(statement)) {
                const yes = condition(statement.expression, bound);
                if (yes === true) return statements(statement.thenStatement);
                if (yes === false) return statements(statement.elseStatement);
                statements(statement.thenStatement); statements(statement.elseStatement); return false;
              }
              if (ts.isReturnStatement(statement)) { if (statement.expression) calls(statement.expression, bound, '有限包装器调用', depth + 1); return true; }
              return false;
            }
            if (ts.isBlock(fn.body)) statements(fn.body); else if (fn.body) calls(fn.body, bound, '有限包装器调用', depth + 1);
          }
        }
      });
    }
    calls(ast);
    // Vue 模板中的有限 v-for 数组也必须展开，而非只在已有字典中匹配通配符。
    if (file.endsWith('.vue')) {
      const template = text.match(/<template\b[^>]*>([\s\S]*)<\/template>/)?.[1];
      if (template) {
        const parsed = baseParse(template, { onError: () => {} });
        const expression = source => ts.createSourceFile('template.ts', `(${source})`, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
        function visit(node, inherited = new Map()) {
          const env = new Map(inherited);
          const loop = node.props?.find(prop => prop.type === 7 && prop.name === 'for')?.exp?.content;
          const match = loop?.match(/^\s*(.+?)\s+(?:in|of)\s+([\s\S]+)$/);
          if (match) {
            const left = match[1].trim().replace(/^\((.*)\)$/, '$1').replace(/^(\w+)\s*,.*$/, '$1');
            const binding = ts.createSourceFile('binding.ts', `const ${left} = [];`, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS).statements[0]?.declarationList?.declarations[0]?.name;
            if (binding) bindPattern(env, binding, expression(match[2]).statements[0]?.expression);
          }
          for (const prop of node.props ?? []) if (prop.type === 7 && prop.exp && prop.name !== 'for') {
            const tree = expression(prop.exp.content);
            calls(tree, env, '有限模板循环');
            if (['BusinessForm', 'ComputeForm', 'BusinessSearchFields'].includes(node.tag) && prop.arg?.content === 'fields')
              walk(tree, child => { if (ts.isObjectLiteralExpression(child)) emitField(fields(child, env)); });
          }
          if (node.type === 5) calls(expression(node.content.content), env, '有限模板循环');
          for (const child of node.children ?? []) visit(child, env);
        }
        visit(parsed);
      }
    }
    // calls 已按语法树保留完整未知参数；不要再用正则把函数声明或截断的嵌套参数误报为额外缺口。
  }
  const dedupe = rows => [...new Map(rows.map(row => [JSON.stringify(row), row])).values()];
  return { references: dedupe(references), unresolved: dedupe(unresolved) };
}
