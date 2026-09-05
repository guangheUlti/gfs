interface Props {
  /** 过渡的开始颜色 */
  startColor?: string
  /** 过渡的结束颜色 */
  endColor?: string
}

/** 左下角装饰（复刻 alist 旧版登录页背景） */
export default function CornerBottom({
  startColor = '#28aff0',
  endColor = '#120fc4',
}: Props) {
  return (
    <svg height='896' width='967.89'>
      <defs>
        <path
          id='login-path-2'
          opacity='1'
          fill-rule='evenodd'
          d='M896,448 C1142.6325445712241,465.5747656464056 695.2579309733121,896 448,896 C200.74206902668806,896 5.684341886080802e-14,695.2579309733121 0,448.0000000000001 C0,200.74206902668806 200.74206902668791,5.684341886080802e-14 447.99999999999994,0 C695.2579309733121,0 475,418 896,448Z'
        />
        <linearGradient id='login-linearGradient-3' x1='0.5' y1='0' x2='0.5' y2='1'>
          <stop offset='0' stopColor={startColor} stopOpacity='1' />
          <stop offset='1' stopColor={endColor} stopOpacity='1' />
        </linearGradient>
      </defs>
      <g opacity='1'>
        <use
          xlinkHref='#login-path-2'
          fill='url(#login-linearGradient-3)'
          fillOpacity='1'
        />
      </g>
    </svg>
  )
}
