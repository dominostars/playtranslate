#!/usr/bin/env node
/*
 * gen_japanese_transforms.mjs - generate the Kotlin data port of Yomitan's
 * Japanese deinflection rule table (JapaneseTransforms.kt).
 *
 * Usage:
 *   node scripts/gen_japanese_transforms.mjs <japanese-transforms.js> <output.kt> <commit-sha>
 *
 * <japanese-transforms.js> is yomitan's ext/js/language/ja/japanese-transforms.js
 * at <commit-sha> (the 40-hex upstream commit it was downloaded at; the file
 * itself is not committed here). It is read as TEXT and never imported or
 * executed: its top-level constants and the `japaneseTransforms` literal are
 * parsed by the small recursive-descent parser below, which accepts only the
 * subset of JavaScript the table uses (objects, arrays, quoted strings, `+`
 * concatenation, identifiers naming earlier constants, calls, template
 * literals that are skipped unevaluated) and fails on anything else.
 *
 * Every rule becomes one of the two kinds the Kotlin engine knows:
 * suffixInflection(in, out, conditionsIn, conditionsOut) becomes Suffix and
 * wholeWordInflection(in, out, conditionsIn, conditionsOut) becomes WholeWord.
 * The two helper spreads the table uses,
 * ...irregularVerbSuffixInflections(suffix, conditionsIn, conditionsOut) and
 * ...specialHonorificMasuInflections(conditionsIn, conditionsOut), are
 * expanded IN PLACE from the same file's data constants (ikuVerbs,
 * godanUSpecialVerbs, fuVerbTeConjugations, specialHonorificMasuVerbs), so
 * every rule keeps its upstream index (the engine's cycle guard and trace
 * frames carry it). The expansion below restates the two helpers; each
 * helper's source is pinned by a SHA-256 of its whitespace-collapsed text, so
 * an upstream change to either helper stops the generator instead of being
 * expanded the old way.
 *
 * Self-checks (non-zero exit on failure): the descriptor carries only
 * language/conditions/transforms, each condition only
 * name/i18n/isDictionaryForm/subConditions, each transform only
 * name/description/i18n/rules; every condition named by a rule or by a
 * subConditions list is defined; every rule's inflected text is non-empty,
 * inside the Basic Multilingual Plane and free of regular-expression
 * metacharacters (upstream compiles it into a RegExp, the port compares
 * strings), and every rule string is BMP-only; the number of direct rule
 * calls and of helper spreads parsed equals the number of
 * `suffixInflection(`/`wholeWordInflection(` calls and of `...name(` spreads
 * in the table's text.
 *
 * The emitted Kotlin is derived from Yomitan (https://github.com/yomidevs/yomitan),
 * licensed under the GNU General Public License v3.0; the input file's
 * license comment is carried verbatim into the output's header.
 */

import {createHash} from 'node:crypto';
import {mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {dirname} from 'node:path';

const UPSTREAM_REPO = 'yomidevs/yomitan';
const UPSTREAM_PATH = 'ext/js/language/ja/japanese-transforms.js';
const KOTLIN_PACKAGE = 'com.playtranslate.dictionary.deinflect';

/**
 * SHA-256 of each expanded helper's source, from `function` to its closing
 * brace with every whitespace run collapsed to one space, at
 * 833409247c5e30a976551a6475843b3a4896ef2f. A mismatch means the helper
 * changed upstream: re-read it and update expandHelper before the hash.
 */
const HELPER_SOURCE_SHA256 = {
    irregularVerbSuffixInflections: 'a3fbc0e011a6bd3d557fbd64b07c2dbf19ab278c680ed4d7cd992e627e184a66',
    specialHonorificMasuInflections: '296a379cd3daa579c5cfcd27dd52e4e8a62e1968f9559bc591a0608d24e18f96',
};

function fail(message) {
    console.error(`gen_japanese_transforms: ${message}`);
    process.exit(1);
}

const [inputPath, outputPath, commit] = process.argv.slice(2);
if (!inputPath || !outputPath || !/^[0-9a-f]{40}$/.test(commit ?? '')) {
    fail('usage: node scripts/gen_japanese_transforms.mjs <japanese-transforms.js> <output.kt> <40-hex commit-sha>');
}
const src = readFileSync(inputPath, 'utf8');

function lineOf(pos) {
    let line = 1;
    for (let i = 0; i < pos && i < src.length; ++i) {
        if (src.charCodeAt(i) === 10) { ++line; }
    }
    return line;
}

// ---------- lexical skipping (strings, template literals, comments) ----------

/** pos at an opening quote; returns the index after the closing quote. */
function skipString(pos) {
    const quote = src[pos];
    let i = pos + 1;
    while (i < src.length) {
        const c = src[i];
        if (c === '\\') { i += 2; continue; }
        if (c === quote) { return i + 1; }
        if (c === '\n') { fail(`newline in string at line ${lineOf(pos)}`); }
        ++i;
    }
    return fail(`unterminated string at line ${lineOf(pos)}`);
}

/** pos at an opening backtick; returns the index after the closing one. */
function skipTemplate(pos) {
    let i = pos + 1;
    while (i < src.length) {
        const c = src[i];
        if (c === '\\') { i += 2; continue; }
        if (c === '`') { return i + 1; }
        if (c === '$' && src[i + 1] === '{') { i = skipBalanced(i + 1); continue; }
        ++i;
    }
    return fail(`unterminated template literal at line ${lineOf(pos)}`);
}

/** Returns the index after a comment starting at pos, or -1 when none starts there. */
function skipComment(pos) {
    if (src.startsWith('//', pos)) {
        const newline = src.indexOf('\n', pos);
        return newline === -1 ? src.length : newline + 1;
    }
    if (src.startsWith('/*', pos)) {
        const end = src.indexOf('*/', pos + 2);
        if (end === -1) { fail(`unterminated comment at line ${lineOf(pos)}`); }
        return end + 2;
    }
    return -1;
}

/** pos at an opening ( [ or {; returns the index after its matching closer. */
function skipBalanced(pos) {
    const open = src[pos];
    const close = {'(': ')', '[': ']', '{': '}'}[open];
    if (!close) { fail(`expected a bracket at line ${lineOf(pos)}, found ${JSON.stringify(open)}`); }
    let i = pos + 1;
    while (i < src.length) {
        const c = src[i];
        const afterComment = skipComment(i);
        if (afterComment !== -1) { i = afterComment; continue; }
        if (c === '\'' || c === '"') { i = skipString(i); continue; }
        if (c === '`') { i = skipTemplate(i); continue; }
        if (c === '(' || c === '[' || c === '{') { i = skipBalanced(i); continue; }
        if (c === close) { return i + 1; }
        if (c === ')' || c === ']' || c === '}') { fail(`mismatched ${c} at line ${lineOf(i)}`); }
        ++i;
    }
    return fail(`unbalanced ${open} at line ${lineOf(pos)}`);
}

// ---------- top-level declarations ----------

/** pos at `function`; returns the index after the function body's closing brace. */
function functionEnd(pos) {
    let i = skipBalanced(src.indexOf('(', pos));
    while (/\s/.test(src[i])) { ++i; }
    if (src[i] !== '{') { fail(`expected a function body at line ${lineOf(i)}`); }
    return skipBalanced(i);
}

/** Top-level `const NAME =` and `function NAME(` declarations (column 0 only), in source order. */
function topLevelDeclarations() {
    const out = new Map();
    for (const m of src.matchAll(/^(?:export\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/gm)) {
        if (out.has(m[1])) { fail(`duplicate top-level name ${m[1]}`); }
        out.set(m[1], {kind: 'function', start: m.index, end: functionEnd(m.index)});
    }
    for (const m of src.matchAll(/^(?:export\s+)?const\s+([A-Za-z_$][\w$]*)\s*=/gm)) {
        if (out.has(m[1])) { fail(`duplicate top-level name ${m[1]}`); }
        out.set(m[1], {kind: 'const', start: m.index, valueStart: m.index + m[0].length});
    }
    return new Map([...out.entries()].sort((a, b) => a[1].start - b[1].start));
}

// ---------- expression parser (the literal subset the table uses) ----------

class ExprParser {
    constructor(pos) { this.pos = pos; }

    err(message) { fail(`${message} at line ${lineOf(this.pos)}`); }

    ws() {
        for (;;) {
            while (/\s/.test(src[this.pos] ?? '')) { ++this.pos; }
            const afterComment = skipComment(this.pos);
            if (afterComment === -1) { return; }
            this.pos = afterComment;
        }
    }

    expr() {
        this.ws();
        const start = this.pos;
        const parts = [this.primary()];
        for (;;) {
            this.ws();
            if (src[this.pos] !== '+') { break; }
            ++this.pos;
            parts.push(this.primary());
        }
        return parts.length === 1 ? parts[0] : {type: 'concat', parts, start, end: this.pos};
    }

    primary() {
        this.ws();
        const start = this.pos;
        const c = src[this.pos];
        if (c === '\'' || c === '"') {
            const end = skipString(this.pos);
            // Decoded without eval: JSON.parse after normalizing the quote form.
            let body = src.slice(this.pos + 1, end - 1);
            if (c === '\'') { body = body.replace(/\\'/g, '\'').replace(/"/g, '\\"'); }
            let value;
            try { value = JSON.parse(`"${body}"`); } catch { this.err('unsupported escape in string'); }
            this.pos = end;
            return {type: 'string', value, start, end};
        }
        if (c === '`') {
            this.pos = skipTemplate(this.pos);
            return {type: 'template', start, end: this.pos};
        }
        if (c === '[') { return this.array(); }
        if (c === '{') { return this.object(); }
        const m = /^[A-Za-z_$][\w$]*/.exec(src.slice(this.pos, this.pos + 80));
        if (m === null) { this.err(`unsupported syntax ${JSON.stringify(c)}`); }
        this.pos += m[0].length;
        if (m[0] === 'true' || m[0] === 'false') { return {type: 'bool', value: m[0] === 'true', start, end: this.pos}; }
        if (m[0] === 'null') { return {type: 'null', start, end: this.pos}; }
        this.ws();
        if (src[this.pos] === '(') {
            ++this.pos;
            const args = [];
            for (;;) {
                this.ws();
                if (src[this.pos] === ')') { ++this.pos; break; }
                args.push(this.expr());
                this.ws();
                if (src[this.pos] === ',') { ++this.pos; continue; }
                if (src[this.pos] === ')') { ++this.pos; break; }
                this.err('expected , or ) in a call');
            }
            return {type: 'call', callee: m[0], args, start, end: this.pos};
        }
        return {type: 'ident', name: m[0], start, end: this.pos};
    }

    array() {
        const start = this.pos;
        ++this.pos;
        const elements = [];
        for (;;) {
            this.ws();
            if (src[this.pos] === ']') { ++this.pos; break; }
            const elementStart = this.pos;
            const spread = src.startsWith('...', this.pos);
            if (spread) { this.pos += 3; }
            const node = this.expr();
            elements.push({spread, node, start: elementStart, end: this.pos});
            this.ws();
            if (src[this.pos] === ',') { ++this.pos; continue; }
            if (src[this.pos] === ']') { ++this.pos; break; }
            this.err('expected , or ] in an array');
        }
        return {type: 'array', elements, start, end: this.pos};
    }

    object() {
        const start = this.pos;
        ++this.pos;
        const props = [];
        for (;;) {
            this.ws();
            if (src[this.pos] === '}') { ++this.pos; break; }
            let key;
            const c = src[this.pos];
            if (c === '\'' || c === '"') {
                key = this.primary().value;
            } else {
                const m = /^[A-Za-z_$][\w$]*/.exec(src.slice(this.pos, this.pos + 80));
                if (m === null) { this.err('expected an object key'); }
                key = m[0];
                this.pos += key.length;
            }
            if (props.some((p) => p.key === key)) { this.err(`duplicate key ${JSON.stringify(key)}`); }
            this.ws();
            if (src[this.pos] === ':') {
                ++this.pos;
                props.push({key, node: this.expr()});
            } else {
                props.push({key, node: {type: 'ident', name: key}});
            }
            this.ws();
            if (src[this.pos] === ',') { ++this.pos; continue; }
            if (src[this.pos] === '}') { ++this.pos; break; }
            this.err('expected , or } in an object');
        }
        return {type: 'object', props, start, end: this.pos};
    }
}

const UNRESOLVED = Symbol('unresolved');

/** Literal value of a node, or UNRESOLVED for calls, spreads, templates and unknown identifiers. */
function evaluate(node, consts) {
    switch (node.type) {
        case 'string':
        case 'bool':
            return node.value;
        case 'null':
            return null;
        case 'concat': {
            let s = '';
            for (const part of node.parts) {
                const v = evaluate(part, consts);
                if (typeof v !== 'string') { return UNRESOLVED; }
                s += v;
            }
            return s;
        }
        case 'ident':
            return consts.has(node.name) ? consts.get(node.name) : UNRESOLVED;
        case 'array': {
            const out = [];
            for (const e of node.elements) {
                if (e.spread) { return UNRESOLVED; }
                const v = evaluate(e.node, consts);
                if (v === UNRESOLVED) { return UNRESOLVED; }
                out.push(v);
            }
            return out;
        }
        case 'object': {
            const out = {};
            for (const p of node.props) {
                const v = evaluate(p.node, consts);
                if (v === UNRESOLVED) { return UNRESOLVED; }
                out[p.key] = v;
            }
            return out;
        }
        default:
            return UNRESOLVED;
    }
}

const isStringArray = (v) => Array.isArray(v) && v.every((x) => typeof x === 'string');
const propsOf = (node) => new Map(node.props.map((p) => [p.key, p.node]));

function requireKeys(what, props, allowed, required) {
    const extra = [...props.keys()].filter((k) => !allowed.includes(k));
    if (extra.length > 0) { fail(`${what} has unexpected keys ${JSON.stringify(extra)}`); }
    const missing = required.filter((k) => !props.has(k));
    if (missing.length > 0) { fail(`${what} is missing keys ${JSON.stringify(missing)}`); }
}

// ---------- constants ----------

const declarations = topLevelDeclarations();
const consts = new Map();
for (const [name, d] of declarations) {
    if (d.kind !== 'const' || name === 'japaneseTransforms') { continue; }
    const v = evaluate(new ExprParser(d.valueStart).expr(), consts);
    if (v !== UNRESOLVED) { consts.set(name, v); }
}

function stringArrayConst(name) {
    const v = consts.get(name);
    if (!isStringArray(v) || v.length === 0) { fail(`constant ${name} is not a non-empty string array`); }
    return v;
}
const ikuVerbs = stringArrayConst('ikuVerbs');
const godanUSpecialVerbs = stringArrayConst('godanUSpecialVerbs');
const specialHonorificMasuVerbs = stringArrayConst('specialHonorificMasuVerbs');
const fuVerbTeConjugations = consts.get('fuVerbTeConjugations');
if (!Array.isArray(fuVerbTeConjugations) || fuVerbTeConjugations.length === 0 ||
    !fuVerbTeConjugations.every((pair) => isStringArray(pair) && pair.length === 2)) {
    fail('constant fuVerbTeConjugations is not a non-empty array of string pairs');
}

// ---------- helpers: pinned source, restated expansion ----------

for (const [name, expected] of Object.entries(HELPER_SOURCE_SHA256)) {
    const d = declarations.get(name);
    if (d?.kind !== 'function') { fail(`helper ${name} is not a top-level function`); }
    const normalized = src.slice(d.start, d.end).replace(/\s+/g, ' ');
    const actual = createHash('sha256').update(normalized, 'utf8').digest('hex');
    if (actual !== expected) {
        fail(`helper ${name} changed upstream (sha256 ${actual}, pinned ${expected}); re-read it and update expandHelper`);
    }
}

const suffixRule = (inText, outText, conditionsIn, conditionsOut) => ({kind: 'suffix', in: inText, out: outText, conditionsIn, conditionsOut});
const wholeWordRule = (inText, outText, conditionsIn, conditionsOut) => ({kind: 'wholeWord', in: inText, out: outText, conditionsIn, conditionsOut});

/** The rules a helper spread contributes, in the order the helper returns them. */
function expandHelper(callee, args) {
    if (callee === 'irregularVerbSuffixInflections') {
        const [suffix, conditionsIn, conditionsOut] = args;
        if (typeof suffix !== 'string' || !isStringArray(conditionsIn) || !isStringArray(conditionsOut)) { return null; }
        return [
            ...ikuVerbs.map((verb) => suffixRule(`${verb[0]}っ${suffix}`, verb, conditionsIn, conditionsOut)),
            ...godanUSpecialVerbs.map((verb) => suffixRule(`${verb}${suffix}`, verb, conditionsIn, conditionsOut)),
            ...fuVerbTeConjugations.map(([verb, teRoot]) => suffixRule(`${teRoot}${suffix}`, verb, conditionsIn, conditionsOut)),
        ];
    }
    if (callee === 'specialHonorificMasuInflections') {
        const [conditionsIn, conditionsOut] = args;
        if (!isStringArray(conditionsIn) || !isStringArray(conditionsOut)) { return null; }
        return specialHonorificMasuVerbs.map((verb) => wholeWordRule(`${verb.slice(0, -1)}います`, verb, conditionsIn, conditionsOut));
    }
    return null;
}

// ---------- conditions ----------

const conditionsValue = consts.get('conditions');
if (conditionsValue === undefined || conditionsValue === null || typeof conditionsValue !== 'object' || Array.isArray(conditionsValue)) {
    fail('conditions did not resolve to an object literal');
}
const conditions = Object.entries(conditionsValue).map(([key, c]) => {
    const props = new Map(Object.entries(c));
    requireKeys(`condition ${key}`, props, ['name', 'i18n', 'isDictionaryForm', 'subConditions'], ['name', 'isDictionaryForm']);
    if (typeof c.name !== 'string' || typeof c.isDictionaryForm !== 'boolean') { fail(`condition ${key} has a non-string name or a non-boolean isDictionaryForm`); }
    if (props.has('subConditions') && !isStringArray(c.subConditions)) { fail(`condition ${key} has a non-string-array subConditions`); }
    return {key, name: c.name, isDictionaryForm: c.isDictionaryForm, subConditions: c.subConditions ?? null};
});
const conditionKeys = new Set(conditions.map((c) => c.key));
for (const c of conditions) {
    for (const sub of c.subConditions ?? []) {
        if (!conditionKeys.has(sub)) { fail(`condition ${c.key} names undefined subCondition ${sub}`); }
    }
}

// ---------- transforms ----------

const tableDeclaration = declarations.get('japaneseTransforms');
if (tableDeclaration?.kind !== 'const') { fail('japaneseTransforms is not a top-level const'); }
const tableNode = new ExprParser(tableDeclaration.valueStart).expr();
if (tableNode.type !== 'object') { fail('japaneseTransforms is not an object literal'); }
const tableProps = propsOf(tableNode);
requireKeys('japaneseTransforms', tableProps, ['language', 'conditions', 'transforms'], ['language', 'conditions', 'transforms']);
if (evaluate(tableProps.get('language'), consts) !== 'ja') { fail('japaneseTransforms.language is not \'ja\''); }
const conditionsRef = tableProps.get('conditions');
if (conditionsRef.type !== 'ident' || conditionsRef.name !== 'conditions') { fail('japaneseTransforms.conditions is not the conditions constant'); }
const transformsNode = tableProps.get('transforms');
if (transformsNode.type !== 'object') { fail('japaneseTransforms.transforms is not an object literal'); }

let directRuleCalls = 0;
let helperSpreads = 0;
const transforms = transformsNode.props.map(({key, node}) => {
    if (node.type !== 'object') { fail(`transform ${key} is not an object literal`); }
    const props = propsOf(node);
    requireKeys(`transform ${key}`, props, ['name', 'description', 'i18n', 'rules'], ['name', 'rules']);
    const name = evaluate(props.get('name'), consts);
    if (typeof name !== 'string') { fail(`transform ${key} has a name that is not a string literal`); }
    const rulesNode = props.get('rules');
    if (rulesNode.type !== 'array') { fail(`transform ${key} rules is not an array literal`); }
    const rules = [];
    const expansions = [];
    for (const element of rulesNode.elements) {
        const n = element.node;
        if (n.type !== 'call') { fail(`transform ${key} has a rule that is not a call at line ${lineOf(element.start)}`); }
        const args = n.args.map((a) => evaluate(a, consts));
        if (!element.spread && (n.callee === 'suffixInflection' || n.callee === 'wholeWordInflection') && args.length === 4) {
            const [inText, outText, conditionsIn, conditionsOut] = args;
            if (typeof inText !== 'string' || typeof outText !== 'string' || !isStringArray(conditionsIn) || !isStringArray(conditionsOut)) {
                fail(`transform ${key} has a ${n.callee} with non-literal arguments at line ${lineOf(element.start)}`);
            }
            const make = n.callee === 'suffixInflection' ? suffixRule : wholeWordRule;
            rules.push(make(inText, outText, conditionsIn, conditionsOut));
            ++directRuleCalls;
            continue;
        }
        if (element.spread) {
            const expanded = expandHelper(n.callee, args);
            if (expanded === null) { fail(`transform ${key} spreads an unknown helper or non-literal arguments at line ${lineOf(element.start)}`); }
            expansions.push({first: rules.length, last: rules.length + expanded.length - 1, source: src.slice(element.start, element.end).replace(/\s+/g, ' ')});
            rules.push(...expanded);
            ++helperSpreads;
            continue;
        }
        fail(`transform ${key} has an unsupported rule ${n.callee} at line ${lineOf(element.start)}`);
    }
    return {key, name, rules, expansions};
});

// ---------- self-checks ----------

const tableText = src.slice(tableNode.start, tableNode.end);
const textRuleCalls = (tableText.match(/(?<![\w$])(?:suffixInflection|wholeWordInflection)\(/g) ?? []).length;
const textSpreads = (tableText.match(/\.\.\.[A-Za-z_$][\w$]*\(/g) ?? []).length;
if (textRuleCalls !== directRuleCalls) { fail(`parsed ${directRuleCalls} direct rule calls but the table text has ${textRuleCalls}`); }
if (textSpreads !== helperSpreads) { fail(`parsed ${helperSpreads} helper spreads but the table text has ${textSpreads}`); }

const REGEX_META = /[.*+?^${}()|[\]\\/]/;
const NON_BMP = /[\uD800-\uDFFF]/;
for (const t of transforms) {
    t.rules.forEach((r, j) => {
        const where = `transform ${t.key} rule ${j}`;
        if (r.in.length === 0) { fail(`${where} has an empty inflected text`); }
        if (REGEX_META.test(r.in)) { fail(`${where} inflected text ${JSON.stringify(r.in)} contains a regular-expression metacharacter`); }
        if (NON_BMP.test(r.in) || NON_BMP.test(r.out)) { fail(`${where} has a character outside the BMP`); }
        for (const c of [...r.conditionsIn, ...r.conditionsOut]) {
            if (!conditionKeys.has(c)) { fail(`${where} names undefined condition ${c}`); }
        }
    });
}

const headerMatch = /^\s*(\/\*[\s\S]*?\*\/)/.exec(src);
if (headerMatch === null || !/Copyright \(C\)/.test(headerMatch[1]) || !/GNU General Public License/.test(headerMatch[1])) {
    fail('the input does not start with a copyright and GPL license comment');
}
const licenseComment = headerMatch[1];

// ---------- Kotlin output ----------

function kt(s) {
    if (/[\u0000-\u001f\u007f]/.test(s)) { fail(`control character in ${JSON.stringify(s)}`); }
    return `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')}"`;
}
const ktList = (items) => (items.length === 0 ? 'emptyList()' : `listOf(${items.map(kt).join(', ')})`);
const comment = (s) => s.replace(/\*\//g, '* /');

const allRules = transforms.flatMap((t) => t.rules);
const suffixCount = allRules.filter((r) => r.kind === 'suffix').length;
const wholeWordCount = allRules.filter((r) => r.kind === 'wholeWord').length;
const leafCount = conditions.filter((c) => c.subConditions === null).length;
const width = String(transforms.length - 1).length;
const functionName = (i) => `transform${String(i).padStart(width, '0')}`;

const lines = [];
lines.push(licenseComment);
lines.push('');
lines.push(`// Generated by scripts/gen_japanese_transforms.mjs from ${UPSTREAM_REPO} ${UPSTREAM_PATH} at ${commit}; do not edit by hand.`);
lines.push('');
lines.push(`package ${KOTLIN_PACKAGE}`);
lines.push('');
lines.push(`import ${KOTLIN_PACKAGE}.Rule.Suffix`);
lines.push(`import ${KOTLIN_PACKAGE}.Rule.WholeWord`);
lines.push('');
lines.push('/**');
lines.push(` * Yomitan's Japanese deinflection table as data: ${conditions.length} conditions and`);
lines.push(` * ${transforms.length} transforms holding ${allRules.length} rules (${suffixCount} suffix, ${wholeWordCount} whole-word), in`);
lines.push(' * upstream order. The table\'s helper spreads are expanded in place, so a');
lines.push(' * rule\'s position in its transform\'s list is its upstream rule index.');
lines.push(' */');
lines.push('internal object JapaneseTransforms {');
lines.push(`    const val UPSTREAM_COMMIT = ${kt(commit)}`);
lines.push('');
lines.push('    val descriptor: TransformDescriptor = TransformDescriptor(');
lines.push('        conditions = conditions(),');
lines.push('        transforms = listOf(');
transforms.forEach((_, i) => lines.push(`            ${functionName(i)}(),`));
lines.push('        ),');
lines.push('    )');
lines.push('');
lines.push('    private fun conditions(): List<Condition> = listOf(');
for (const c of conditions) {
    const sub = c.subConditions === null ? 'null' : ktList(c.subConditions);
    lines.push(`        Condition(${kt(c.key)}, isDictionaryForm = ${c.isDictionaryForm}, subConditions = ${sub}), // ${comment(c.name)}`);
}
lines.push('    )');
transforms.forEach((t, i) => {
    lines.push('');
    lines.push(`    private fun ${functionName(i)}(): Transform = Transform(`);
    lines.push(`        key = ${kt(t.key)},`);
    lines.push(`        name = ${kt(t.name)},`);
    lines.push('        rules = listOf(');
    t.rules.forEach((r, j) => {
        const expansion = t.expansions.find((e) => e.first === j);
        if (expansion) {
            lines.push(`            // rules ${expansion.first} to ${expansion.last}: ${comment(expansion.source)}, expanded`);
        }
        const kind = r.kind === 'suffix' ? 'Suffix' : 'WholeWord';
        lines.push(`            ${kind}(${kt(r.in)}, ${kt(r.out)}, ${ktList(r.conditionsIn)}, ${ktList(r.conditionsOut)}),`);
    });
    lines.push('        ),');
    lines.push('    )');
});
lines.push('}');
const kotlin = lines.join('\n') + '\n';

mkdirSync(dirname(outputPath), {recursive: true});
writeFileSync(outputPath, kotlin, 'utf8');

const count = (predicate) => allRules.filter(predicate).length;
console.log(`conditions=${conditions.length} (leaf=${leafCount}, isDictionaryForm=${conditions.filter((c) => c.isDictionaryForm).length})`);
console.log(`transforms=${transforms.length}`);
console.log(`rule entries before expansion=${directRuleCalls + helperSpreads} (direct calls=${directRuleCalls}, helper spreads=${helperSpreads})`);
console.log(`rules after expansion=${allRules.length} (suffix=${suffixCount}, wholeWord=${wholeWordCount})`);
console.log(`empty conditionsIn=${count((r) => r.conditionsIn.length === 0)} empty conditionsOut=${count((r) => r.conditionsOut.length === 0)} empty out=${count((r) => r.out === '')} lengthening=${count((r) => r.out.length > r.in.length)}`);
console.log(`wrote ${outputPath} (${lines.length} lines)`);
