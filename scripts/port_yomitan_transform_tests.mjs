#!/usr/bin/env node
/*
 * port_yomitan_transform_tests.mjs - extract Yomitan's Japanese deinflection
 * test table into a JSON fixture for the Kotlin port of its transformer.
 *
 * Usage:
 *   node scripts/port_yomitan_transform_tests.mjs <input.test.js> <output.json> <commit-sha>
 *
 * <input.test.js> is yomitan's test/language/japanese-transforms.test.js at
 * <commit-sha> (the 40-hex upstream commit it was downloaded at). The file is
 * read as TEXT and never imported or executed: the `const tests = [...]`
 * literal is parsed by the small recursive-descent parser below, which accepts
 * only the subset of JavaScript literal syntax the table uses (objects,
 * arrays, single/double-quoted strings, true/false/null, comments, trailing
 * commas) and fails on anything else, so an upstream shape change stops the
 * port instead of being skipped silently.
 *
 * Output (cases kept in upstream order, duplicates included):
 *   {"upstream": {"repo", "commit", "path", "license"},
 *    "categories": [{"category", "valid", "tests": [{"term", "source", "rule", "reasons"}]}]}
 *
 * Self-checks (non-zero exit on failure): every category has exactly the keys
 * category/valid/tests and every case exactly term/source/rule/reasons with
 * the upstream types (rule and reasons may be null); the number of extracted
 * cases equals the number of `{term:` occurrences in the input text, and the
 * number of categories equals the number of `category:` keys.
 *
 * The emitted fixture is derived from Yomitan (https://github.com/yomidevs/yomitan),
 * Copyright (C) 2023-2026 Yomitan Authors, Copyright (C) 2020-2022 Yomichan
 * Authors, licensed under the GNU General Public License v3.0.
 */

import {mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {dirname} from 'node:path';
import {isDeepStrictEqual} from 'node:util';

const UPSTREAM_REPO = 'yomidevs/yomitan';
const UPSTREAM_PATH = 'test/language/japanese-transforms.test.js';
const UPSTREAM_LICENSE = 'GPL-3.0';

function fail(message) {
    console.error(`port_yomitan_transform_tests: ${message}`);
    process.exit(1);
}

function lineCol(src, pos) {
    let line = 1;
    let lineStart = 0;
    for (let i = 0; i < pos && i < src.length; ++i) {
        if (src[i] === '\n') { ++line; lineStart = i + 1; }
    }
    return `line ${line}, column ${pos - lineStart + 1}`;
}

class LiteralParser {
    constructor(src, pos) {
        this.src = src;
        this.pos = pos;
        /** Start offset of each parsed object, for error messages. */
        this.positions = new WeakMap();
    }

    error(message, pos = this.pos) {
        fail(`${message} at ${lineCol(this.src, pos)}`);
    }

    skipTrivia() {
        for (;;) {
            const c = this.src[this.pos];
            if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '﻿') {
                ++this.pos;
            } else if (this.src.startsWith('//', this.pos)) {
                const nl = this.src.indexOf('\n', this.pos);
                this.pos = nl === -1 ? this.src.length : nl + 1;
            } else if (this.src.startsWith('/*', this.pos)) {
                const end = this.src.indexOf('*/', this.pos + 2);
                if (end === -1) { this.error('unterminated block comment'); }
                this.pos = end + 2;
            } else {
                return;
            }
        }
    }

    parseValue() {
        this.skipTrivia();
        const c = this.src[this.pos];
        if (c === '[') { return this.parseArray(); }
        if (c === '{') { return this.parseObject(); }
        if (c === '\'' || c === '"') { return this.parseString(); }
        const keyword = /^(?:true|false|null)(?![A-Za-z0-9_$])/.exec(this.src.slice(this.pos, this.pos + 6));
        if (keyword !== null) {
            this.pos += keyword[0].length;
            return keyword[0] === 'null' ? null : keyword[0] === 'true';
        }
        if (typeof c === 'undefined') { this.error('unexpected end of input'); }
        this.error(`unsupported syntax starting with ${JSON.stringify(c)}`);
    }

    parseArray() {
        this.pos++; // [
        const items = [];
        for (;;) {
            this.skipTrivia();
            if (this.src[this.pos] === ']') { this.pos++; return items; }
            items.push(this.parseValue());
            this.skipTrivia();
            const c = this.src[this.pos];
            if (c === ',') { this.pos++; continue; }
            if (c === ']') { this.pos++; return items; }
            this.error(`expected ',' or ']' but found ${JSON.stringify(c)}`);
        }
    }

    parseObject() {
        const start = this.pos;
        this.pos++; // {
        const obj = Object.create(null);
        for (;;) {
            this.skipTrivia();
            if (this.src[this.pos] === '}') { this.pos++; break; }
            const keyPos = this.pos;
            let key;
            const c = this.src[this.pos];
            if (c === '\'' || c === '"') {
                key = this.parseString();
            } else {
                const m = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(this.src.slice(this.pos, this.pos + 64));
                if (m === null) { this.error(`expected a property key but found ${JSON.stringify(c)}`); }
                key = m[0];
                this.pos += key.length;
            }
            if (Object.prototype.hasOwnProperty.call(obj, key)) { this.error(`duplicate key ${JSON.stringify(key)}`, keyPos); }
            this.skipTrivia();
            if (this.src[this.pos] !== ':') { this.error(`expected ':' after key ${JSON.stringify(key)}`); }
            this.pos++;
            obj[key] = this.parseValue();
            this.skipTrivia();
            const d = this.src[this.pos];
            if (d === ',') { this.pos++; continue; }
            if (d === '}') { this.pos++; break; }
            this.error(`expected ',' or '}' but found ${JSON.stringify(d)}`);
        }
        this.positions.set(obj, start);
        return obj;
    }

    parseString() {
        const quote = this.src[this.pos];
        const start = this.pos;
        this.pos++;
        let out = '';
        for (;;) {
            if (this.pos >= this.src.length) { this.error('unterminated string', start); }
            const c = this.src[this.pos];
            if (c === quote) { this.pos++; return out; }
            if (c === '\n' || c === '\r') { this.error('newline inside string literal', start); }
            if (c !== '\\') { out += c; this.pos++; continue; }
            const e = this.src[this.pos + 1];
            this.pos += 2;
            switch (e) {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'v': out += '\v'; break;
                case '0':
                    if (/[0-9]/.test(this.src[this.pos])) { this.error('octal escapes are not supported'); }
                    out += '\0';
                    break;
                case 'x': {
                    const hex = this.src.slice(this.pos, this.pos + 2);
                    if (!/^[0-9A-Fa-f]{2}$/.test(hex)) { this.error('malformed \\x escape'); }
                    out += String.fromCharCode(parseInt(hex, 16));
                    this.pos += 2;
                    break;
                }
                case 'u': {
                    let hex;
                    if (this.src[this.pos] === '{') {
                        const end = this.src.indexOf('}', this.pos);
                        hex = end === -1 ? '' : this.src.slice(this.pos + 1, end);
                        if (!/^[0-9A-Fa-f]{1,6}$/.test(hex)) { this.error('malformed \\u{} escape'); }
                        this.pos = end + 1;
                        out += String.fromCodePoint(parseInt(hex, 16));
                    } else {
                        hex = this.src.slice(this.pos, this.pos + 4);
                        if (!/^[0-9A-Fa-f]{4}$/.test(hex)) { this.error('malformed \\u escape'); }
                        this.pos += 4;
                        out += String.fromCharCode(parseInt(hex, 16));
                    }
                    break;
                }
                case '\r':
                    if (this.src[this.pos] === '\n') { this.pos++; }
                    break; // line continuation
                case '\n':
                case ' ':
                case ' ':
                    break; // line continuation
                default:
                    if (typeof e === 'undefined') { this.error('unterminated string', start); }
                    if (/[1-9]/.test(e)) { this.error('octal escapes are not supported'); }
                    out += e; // \' \" \\ and identity escapes
            }
        }
    }
}

function exactKeys(obj, expected) {
    const keys = Object.keys(obj);
    return keys.length === expected.length && expected.every((k) => keys.includes(k));
}

function main() {
    const args = process.argv.slice(2);
    if (args.length !== 3) {
        fail('usage: node scripts/port_yomitan_transform_tests.mjs <input.test.js> <output.json> <commit-sha>');
    }
    const [inputPath, outputPath, commit] = args;
    if (!/^[0-9a-f]{40}$/.test(commit)) { fail(`commit must be a full 40-hex sha, got ${JSON.stringify(commit)}`); }

    const src = readFileSync(inputPath, 'utf8');
    const declarations = [...src.matchAll(/\bconst\s+tests\s*=\s*\[/g)];
    if (declarations.length !== 1) { fail(`expected exactly one 'const tests = [' in the input, found ${declarations.length}`); }
    const arrayStart = declarations[0].index + declarations[0][0].length - 1;

    const parser = new LiteralParser(src, arrayStart);
    const table = parser.parseValue();
    parser.skipTrivia();
    if (src[parser.pos] !== ';') { parser.error('expected \';\' after the tests array'); }

    const where = (obj) => lineCol(src, parser.positions.get(obj) ?? 0);
    const categories = [];
    let caseCount = 0;
    for (const cat of table) {
        if (cat === null || typeof cat !== 'object' || Array.isArray(cat)) { fail('a tests entry is not an object'); }
        if (!exactKeys(cat, ['category', 'valid', 'tests'])) {
            fail(`category at ${where(cat)} has keys ${JSON.stringify(Object.keys(cat))}, expected category/valid/tests`);
        }
        if (typeof cat.category !== 'string') { fail(`category name at ${where(cat)} is not a string`); }
        if (typeof cat.valid !== 'boolean') { fail(`valid at ${where(cat)} is not a boolean`); }
        if (!Array.isArray(cat.tests)) { fail(`tests at ${where(cat)} is not an array`); }
        const tests = [];
        for (const t of cat.tests) {
            if (t === null || typeof t !== 'object' || Array.isArray(t)) { fail(`a case in category ${JSON.stringify(cat.category)} is not an object`); }
            if (!exactKeys(t, ['term', 'source', 'rule', 'reasons'])) {
                fail(`case at ${where(t)} has keys ${JSON.stringify(Object.keys(t))}, expected term/source/rule/reasons`);
            }
            if (typeof t.term !== 'string') { fail(`term at ${where(t)} is not a string`); }
            if (typeof t.source !== 'string') { fail(`source at ${where(t)} is not a string`); }
            if (t.rule !== null && typeof t.rule !== 'string') { fail(`rule at ${where(t)} is neither a string nor null`); }
            if (t.reasons !== null && !(Array.isArray(t.reasons) && t.reasons.every((r) => typeof r === 'string'))) {
                fail(`reasons at ${where(t)} is neither an array of strings nor null`);
            }
            tests.push({
                term: t.term,
                source: t.source,
                rule: t.rule,
                reasons: t.reasons === null ? null : [...t.reasons],
            });
        }
        caseCount += tests.length;
        categories.push({category: cat.category, valid: cat.valid, tests});
    }

    const termOccurrences = (src.match(/\{\s*term\s*:/g) ?? []).length;
    if (caseCount !== termOccurrences) {
        fail(`parity check failed: extracted ${caseCount} cases but the input has ${termOccurrences} '{term:' occurrences`);
    }
    const categoryOccurrences = (src.match(/\bcategory\s*:/g) ?? []).length;
    if (categories.length !== categoryOccurrences) {
        fail(`parity check failed: extracted ${categories.length} categories but the input has ${categoryOccurrences} 'category:' keys`);
    }

    const upstream = {repo: UPSTREAM_REPO, commit, path: UPSTREAM_PATH, license: UPSTREAM_LICENSE};
    // One case per line keeps the fixture diffable against a future re-port.
    const lines = ['{'];
    lines.push(`  "upstream": ${JSON.stringify(upstream, null, 2).replace(/\n/g, '\n  ')},`);
    lines.push('  "categories": [');
    categories.forEach((cat, i) => {
        lines.push('    {');
        lines.push(`      "category": ${JSON.stringify(cat.category)},`);
        lines.push(`      "valid": ${JSON.stringify(cat.valid)},`);
        lines.push('      "tests": [');
        cat.tests.forEach((t, j) => {
            lines.push(`        ${JSON.stringify(t)}${j < cat.tests.length - 1 ? ',' : ''}`);
        });
        lines.push('      ]');
        lines.push(`    }${i < categories.length - 1 ? ',' : ''}`);
    });
    lines.push('  ]');
    lines.push('}');
    const json = lines.join('\n') + '\n';

    if (!isDeepStrictEqual(JSON.parse(json), {upstream, categories})) {
        fail('internal error: the emitted JSON does not round-trip to the extracted table');
    }

    mkdirSync(dirname(outputPath), {recursive: true});
    writeFileSync(outputPath, json, 'utf8');
    const invalid = categories.filter((c) => !c.valid).reduce((n, c) => n + c.tests.length, 0);
    console.log(`wrote ${outputPath}: ${categories.length} categories, ${caseCount} cases (${caseCount - invalid} valid, ${invalid} invalid); parity ${caseCount}/${termOccurrences} '{term:' occurrences`);
}

main();
