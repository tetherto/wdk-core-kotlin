// WDK Worklet Bundle Configuration (Template)
// Consumers of wdk-kotlin-core should customize this file
// with their desired networks and wallet packages.
module.exports = {
  transport: 'jsonrpc',

  // Add/remove networks as needed for your app
  networks: {
    ethereum: { package: '@tetherto/wdk-wallet-evm' },
    polygon: { package: '@tetherto/wdk-wallet-evm' },
    arbitrum: { package: '@tetherto/wdk-wallet-evm' },
    sepolia: { package: '@tetherto/wdk-wallet-evm' },
    'ethereum-erc4337': { package: '@tetherto/wdk-wallet-evm-erc-4337' },
    solana: { package: '@tetherto/wdk-wallet-solana' },
    bitcoin: { package: '@tetherto/wdk-wallet-btc' }
  },

  // Protocol modules (optional)
  protocols: {},

  output: {
    bundle: './.wdk-bundle/wdk-worklet.bundle',
    addons: { android: './android-addons' }
  },

  options: {
    linkAddons: true,
    platforms: ['android'],
    targets: ['android-arm64', 'android-arm', 'android-ia32', 'android-x64'],
    convertEsmToCjs: false
  }
}
