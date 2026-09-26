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

// 1. 创建 Release（tag 不存在时自动创建在 main）
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
console.log(`release created: id=${created.id} ${created.html_url}`)

// 2. 上传 zip 附件
const zip = fs.readFileSync(zipPath)
const assetName = zipPath.split(/[\\/]/).pop()
const uploadRes = await fetch(
  `https://uploads.github.com/repos/${repo}/releases/${created.id}/assets?name=${encodeURIComponent(assetName)}`,
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
