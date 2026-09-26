// SigV4 实测脚本：ListBuckets / PutObject / GetObject / DeleteObject
// 用法：node scripts/oss-sigv4-test.mjs <accessKey> <secretKey> [baseUrl]
// 依赖 node 24 自带 fetch/crypto，无第三方依赖。
import crypto from 'node:crypto'

const [ak = '', sk = '', baseUrl = 'http://127.0.0.1:2000'] = process.argv.slice(2)
const region = 'us-east-1'
const service = 's3'
const bucket = 'gfs'

const sha256Hex = (data) => crypto.createHash('sha256').update(data).digest('hex')
const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest()
const uriEncode = (s, encodeSlash = true) =>
  s.split('').map((ch) => {
    if (/[A-Za-z0-9_.~-]/.test(ch)) return ch
    if (ch === '/') return encodeSlash ? '%2F' : '/'
    return '%' + ch.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')
  }).join('')

/** 按 AWS SigV4 签名并请求 /oss 网关（UNSIGNED-PAYLOAD）；secretKey 参数默认取全局 sk（可传错误值验证拒签） */
async function ossFetch(method, pathAndQuery, body, headers = {}, secretKey = sk) {
  const url = new URL(baseUrl + pathAndQuery)
  const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '')
  const dateStamp = amzDate.slice(0, 8)

  // 规范化查询串
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
    .map((k) => k.toLowerCase())
    .sort()
    .map((k) => `${k}:${String(allHeaders[Object.keys(allHeaders).find((o) => o.toLowerCase() === k)]).trim()}\n`)
    .join('')

  const canonicalUri = url.pathname.replace(/\/+/g, '/').split('/').map((seg, i) => (i === 0 ? '' : uriEncode(seg))).join('/')
  // canonicalHeaders 自带尾部换行，不能用 join('\n')（会多出空行导致签名不一致）
  const canonicalRequest =
    method + '\n' + canonicalUri + '\n' + canonicalQuery + '\n' + canonicalHeaders + signedHeaders + '\n' + 'UNSIGNED-PAYLOAD'

  const scope = `${dateStamp}/${region}/${service}/aws4_request`
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, sha256Hex(canonicalRequest)].join('\n')

  const kDate = hmac(`AWS4${secretKey}`, dateStamp)
  const kRegion = hmac(kDate, region)
  const kService = hmac(kRegion, service)
  const kSigning = hmac(kService, 'aws4_request')
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

// 1. ListBuckets
{
  const res = await ossFetch('GET', '/oss/')
  const text = await res.text()
  assert(res.status === 200 && text.includes('<Name>gfs</Name>'), 'ListBuckets', `status=${res.status}`)
}

// 2. PutObject（含子目录 key，验证父目录自动创建）
const key = 'oss-test/hello.txt'
const payload = Buffer.from('hello gfs oss ' + Date.now())
{
  const res = await ossFetch('PUT',  `/oss/${bucket}/${key}`, payload, { 'content-length': String(payload.length) })
  const text = await res.text()
  assert(res.status === 200, 'PutObject', `status=${res.status} body=${text.slice(0, 120)}`)
}

// 3. GetObject（比对内容）
{
  const res = await ossFetch('GET',  `/oss/${bucket}/${key}`)
  const text = await res.text()
  assert(res.status === 200 && text === payload.toString(), 'GetObject roundtrip', `status=${res.status}`)
}

// 4. Range 读取
{
  const res = await ossFetch('GET',  `/oss/${bucket}/${key}`, undefined, { range: 'bytes=0-4' })
  const text = await res.text()
  assert(res.status === 206 && text === payload.toString().slice(0, 5), 'Range GET 0-4', `status=${res.status} body=${text}`)
}

// 5. ListObjects（prefix 过滤）
{
  const res = await ossFetch('GET',  `/oss/${bucket}?list-type=2&prefix=${encodeURIComponent('oss-test/')}`)
  const text = await res.text()
  assert(res.status === 200 && text.includes('<Key>' + key + '</Key>'), 'ListObjectsV2', `status=${res.status}`)
}

// 6. DeleteObject
{
  const res = await ossFetch('DELETE',  `/oss/${bucket}/${key}`)
  assert(res.status === 204, 'DeleteObject', `status=${res.status}`)
}

// 7. 删除后再 GET 应 404 NoSuchKey
{
  const res = await ossFetch('GET',  `/oss/${bucket}/${key}`)
  const text = await res.text()
  assert(res.status === 404 && text.includes('NoSuchKey'), 'Get after delete -> 404', `status=${res.status}`)
}

// 8. 无签名请求应 403
{
  const res = await fetch(baseUrl + '/oss/')
  assert(res.status === 403, 'Unsigned request rejected', `status=${res.status}`)
}

// 9. 错误 SecretKey 应 SignatureDoesNotMatch
{
  const badSk = sk.slice(0, -1) + (sk.endsWith('a') ? 'b' : 'a')
  const res = await ossFetch('GET', '/oss/', undefined, {}, badSk)
  const text = await res.text()
  assert(res.status === 403 && text.includes('SignatureDoesNotMatch'), 'Wrong secret rejected', `status=${res.status}`)
}

console.log('done.')
