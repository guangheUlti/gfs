import { WebDavServicePage } from './webdav'

/**
 * SFTP 服务配置页（serviceType=sftp），复用 WebDAV 页面的通用布局
 */
export default function SftpServicePage() {
  return <WebDavServicePage serviceType='sftp' />
}
