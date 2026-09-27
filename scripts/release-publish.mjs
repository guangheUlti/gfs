// 创建 GitHub Release（body 取 RELEASE-NOTES）并上传发行 zip 附件
// 用法：GH_TOKEN=xxx node scripts/release-publish.mjs <tag> <zipPath>
import fs from 'node:fs'

const [tag = '', zipPath = ''] = process.argv.slice(2)
const token = process.env.GH_TOKEN
if (!token || !tag || !zipPath) {
  console.error('usage: GH_TOKEN=xxx node scripts/release-publish.mjs <tag> <zipPath>')
  process.exit(1)
}

const repo = 'guangheUlti/gfs'
const headers = {
  Authorization: `token ${token}`,
  'Content-Type': 'application/json',
  Accept: 'application/vnd.github+json',
  'User-Agent': 'gfs-release-script',
}

const notesFile = `release/RELEASE-NOTES-${tag}.md`
const body = fs.existsSync(notesFile)
  ? fs.readFileSync(notesFile, 'utf8')
  : `GFS ${tag}`

// 1. 已存在同名 Release 则复用（更新 body），否则创建（tag 不存在时自动创建在 main）
const listRes = await fetch(`https://api.github.com/repos/${repo}/releases/tags/${encodeURIComponent(tag)}`, { headers })
const existing = listRes.status === 200 ? await listRes.json() : null

let releaseId
if (existing?.id) {
  releaseId = existing.id
  const patchRes = await fetch(`https://api.github.com/repos/${repo}/releases/${releaseId}`, {
    method: 'PATCH',
    headers,
    body: JSON.stringify({ body }),
  })
  if (!patchRes.ok) {
    console.error('update release body failed:', patchRes.status, await patchRes.text().then(t => t.slice(0, 400)))
    process.exit(1)
  }
  console.log(`release exists, body updated: id=${releaseId} ${existing.html_url}`)
} else {
  const createRes = await fetch(`https://api.github.com/repos/${repo}/releases`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      tag_name: tag,
      target_commitish: 'main',
      name: `GFS ${tag.replace(/^v/, '')}`,
      body,
      draft: false,
      prerelease: false,
    }),
  })
  const created = await createRes.json()
  if (!created.id) {
    console.error('create release failed:', JSON.stringify(created).slice(0, 400))
    process.exit(1)
  }
  releaseId = created.id
  console.log(`release created: id=${releaseId} ${created.html_url}`)
}

// 2. 删除同名旧附件后重新上传
const assetsRes = await fetch(`https://api.github.com/repos/${repo}/releases/${releaseId}/assets`, { headers })
const assets = assetsRes.ok ? await assetsRes.json() : []
const assetName = zipPath.split(/[\\/]/).pop()
for (const old of assets.filter(a => a.name === assetName)) {
  const delRes = await fetch(`https://api.github.com/repos/${repo}/releases/assets/${old.id}`, { method: 'DELETE', headers })
  console.log(`old asset deleted: ${old.name} (${delRes.status})`)
}

const zip = fs.readFileSync(zipPath)
const uploadRes = await fetch(
  `https://uploads.github.com/repos/${repo}/releases/${releaseId}/assets?name=${encodeURIComponent(assetName)}`,
  {
    method: 'POST',
    headers: {
      Authorization: `token ${token}`,
      'Content-Type': 'application/zip',
      'Content-Length': zip.length,
      'User-Agent': 'gfs-release-script',
    },
    body: zip,
  }
)
const asset = await uploadRes.json()
if (asset.id) {
  console.log(`asset uploaded: ${asset.name} (${(asset.size / 1024 / 1024).toFixed(1)} MB) state=${asset.state}`)
  console.log(`download: ${asset.browser_download_url}`)
} else {
  console.error('upload failed:', JSON.stringify(asset).slice(0, 400))
  process.exit(1)
}
