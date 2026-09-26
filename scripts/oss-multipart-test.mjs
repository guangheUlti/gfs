// 分片上传三件套实测：POST ?uploads → PUT ?partNumber&uploadId ×2 → POST ?uploadId
// 用法：node scripts/oss-multipart-test.mjs <accessKey> <secretKey> [baseUrl]
import crypto from 'node:crypto'

const [ak = '', sk = '', baseUrl = 'http://127.0.0.1:2000'] = process.argv.slice(2)
const region = 'us-east-1'
const service = 's3'
const bucket = 'gfs'
const key = 'oss-test/multipart-big.txt'

const sha256Hex = (data) => crypto.createHash('sha256').update(data).digest('hex')
const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest()
const uriEncode = (s, encodeSlash = true) =>
  s.split('').map((ch) => {
    if (/[A-Za-z0-9_.~-]/.test(ch)) return ch
    if (ch === '/') return encodeSlash ? '%2F' : '/'
    return '%' + ch.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')
  }).join('')

async function ossFetch(method, pathAndQuery, body, headers = {}) {
  const url = new URL(baseUrl + pathAndQuery)
  const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '')
  const dateStamp = amzDate.slice(0, 8)
  const params = [...url.searchParams.entries()].map(([k, v]) => [uriEncode(k), uriEncode(v)])
  params.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : 1))
  const canonicalQuery = params.map(([k, v]) => `${k}=${v}`).join('&')
  const allHeaders = {
    host: url.host,
    'x-amz-content-sha256': 'UNSIGNED-PAYLOAD',
    'x-amz-date': amzDate,
    ...headers,
  }
  const signedHeaders = Object.keys(allHeaders).map((k) => k.toLowerCase()).sort().join(';')
  const canonicalHeaders = Object.keys(allHeaders)
    .map((k) => k.toLowerCase()).sort()
    .map((k) => `${k}:${String(allHeaders[Object.keys(allHeaders).find((o) => o.toLowerCase() === k)]).trim()}\n`)
    .join('')
  const canonicalUri = url.pathname.split('/').map((seg, i) => (i === 0 ? '' : uriEncode(seg))).join('/')
  const canonicalRequest =
    method + '\n' + canonicalUri + '\n' + canonicalQuery + '\n' + canonicalHeaders + signedHeaders + '\n' + 'UNSIGNED-PAYLOAD'
  const scope = `${dateStamp}/${region}/${service}/aws4_request`
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, sha256Hex(canonicalRequest)].join('\n')
  const kDate = hmac(`AWS4${sk}`, dateStamp)
  const kSigning = hmac(hmac(hmac(kDate, region), service), 'aws4_request')
  const signature = crypto.createHmac('sha256', kSigning).update(stringToSign).digest('hex')
  const authorization = `AWS4-HMAC-SHA256 Credential=${ak}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`
  return fetch(url, {
    method,
    headers: { ...allHeaders, Authorization: authorization },
    body: method === 'GET' || method === 'HEAD' || body == null ? undefined : body,
  })
}

const assert = (cond, label, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}${extra ? '  ' + extra : ''}`)
  if (!cond) process.exitCode = 1
}

const part1 = Buffer.from('A'.repeat(1024))
const part2 = Buffer.from('B'.repeat(512))

// 1. Initiate
let res = await ossFetch('POST', `/oss/${bucket}/${key}?uploads`)
let xml = await res.text()
const uploadId = (xml.match(/<UploadId>([^<]+)<\/UploadId>/) || [])[1]
assert(res.status === 200 && !!uploadId, 'InitiateMultipartUpload', `status=${res.status} uploadId=${uploadId}`)

// 2. UploadPart ×2
res = await ossFetch('PUT', `/oss/${bucket}/${key}?partNumber=1&uploadId=${uploadId}`, part1, { 'content-length': String(part1.length) })
assert(res.status === 200 && res.headers.get('etag'), 'UploadPart 1', `status=${res.status}`)
res = await ossFetch('PUT', `/oss/${bucket}/${key}?partNumber=2&uploadId=${uploadId}`, part2, { 'content-length': String(part2.length) })
assert(res.status === 200 && res.headers.get('etag'), 'UploadPart 2', `status=${res.status}`)

// 3. ListParts
res = await ossFetch('GET', `/oss/${bucket}/${key}?uploadId=${uploadId}`)
xml = await res.text()
assert(res.status === 200 && xml.includes('<PartNumber>1</PartNumber>') && xml.includes('<PartNumber>2</PartNumber>'), 'ListParts', `status=${res.status}`)

// 4. Complete（按 partNumber 升序合并）
const completeBody = '<CompleteMultipartUpload><Part><PartNumber>1</PartNumber></Part><Part><PartNumber>2</PartNumber></Part></CompleteMultipartUpload>'
res = await ossFetch('POST', `/oss/${bucket}/${key}?uploadId=${uploadId}`, completeBody, { 'content-length': String(completeBody.length) })
xml = await res.text()
assert(res.status === 200 && xml.includes('<CompleteMultipartUploadResult'), 'CompleteMultipartUpload', `status=${res.status} body=${xml.slice(0, 100)}`)

// 5. 校验合并内容（Get 全量 = part1+part2）
res = await ossFetch('GET', `/oss/${bucket}/${key}`)
const merged = Buffer.from(await res.arrayBuffer())
assert(res.status === 200 && merged.length === 1536 && merged.slice(0, 1024).toString() === 'A'.repeat(1024) && merged.slice(1024).toString() === 'B'.repeat(512), 'Merged content', `len=${merged.length}`)

// 6. Abort 不存在的会话 → 幂等 204
res = await ossFetch('DELETE', `/oss/${bucket}/${key}?uploadId=nonexistent`)
assert(res.status === 204, 'AbortMultipartUpload (idempotent)', `status=${res.status}`)

// 7. 清理
res = await ossFetch('DELETE', `/oss/${bucket}/${key}`)
assert(res.status === 204, 'Cleanup delete', `status=${res.status}`)

console.log('done.')
