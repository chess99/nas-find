import unittest
from scripts.android_release import verify_metadata


class AndroidRelease(unittest.TestCase):
    def test_only_expected_release_identity_and_certificate_are_accepted(self):
        manifest = "package: name='net.chess99.nasfind' versionCode='3' versionName='0.5.0'\nsdkVersion:'26'\n"
        fingerprint = 'a' * 64
        signature = f'Signer #1 certificate SHA-256 digest: {fingerprint}\n'
        version = {'version': '0.5.0', 'androidVersionCode': 3}
        verify_metadata(manifest, signature, version, fingerprint)
        for altered in (manifest.replace("nasfind'", "nasfind.debug'"), manifest.replace("'3'", "'2'"),
                        manifest.replace('0.5.0', '0.4.2'), manifest + 'application-debuggable\n'):
            with self.assertRaises(ValueError): verify_metadata(altered, signature, version, fingerprint)
        for altered in ('', signature.replace('a' * 64, 'b' * 64), signature + signature.replace('#1', '#2')):
            with self.assertRaises(ValueError): verify_metadata(manifest, altered, version, fingerprint)
