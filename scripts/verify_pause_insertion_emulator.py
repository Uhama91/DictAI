#!/usr/bin/env python3
"""Run the pause insertion fixture after reconnecting accessibility on an emulator."""
import argparse
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--log', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This fixture only changes accessibility settings on an emulator')
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    adb = str(sdk / 'platform-tools/adb')
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open('w') as output:
        process = subprocess.Popen([adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'disableAnalytics', 'true', '-e', 'class',
            'com.kafkasl.phonewhisper.AccessibilityInsertionAndroidTest',
            'com.uhama.whisperpin.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 25
        while 'INSTRUMENTATION_STATUS_CODE: 1' not in args.log.read_text():
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Instrumentation did not start; inspect the log')
            time.sleep(.1)
        # Starting instrumentation kills the target process, including its existing service.
        for command in [('delete', 'secure', 'enabled_accessibility_services'),
                ('put', 'secure', 'enabled_accessibility_services',
                    'com.uhama.whisperpin/com.kafkasl.phonewhisper.WhisperAccessibilityService'),
                ('put', 'secure', 'accessibility_enabled', '1')]:
            subprocess.run([adb, '-s', args.serial, 'shell', 'settings', *command],
                check=True, stdout=subprocess.DEVNULL)
        process.wait(timeout=120)
    text = args.log.read_text()
    if 'OK (5 tests)' not in text:
        raise RuntimeError('Insertion fixture failed; inspect the log')
    print('Five native insertion tests passed on ' + args.serial)


if __name__ == '__main__':
    main()
