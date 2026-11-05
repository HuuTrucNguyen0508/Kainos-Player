from pathlib import Path
import os
import shutil
import subprocess


def test_installer_handles_systemd_specifiers_and_quoted_paths(tmp_path):
    root = tmp_path / 'gateway %h "quoted"'
    root.mkdir()
    scripts = Path(__file__).resolve().parents[1] / 'scripts'
    shutil.copytree(scripts, root / 'scripts')
    (root / '.env').touch()
    bin_dir = tmp_path / 'bin'
    bin_dir.mkdir()
    for name in ('systemctl', 'loginctl'):
        stub = bin_dir / name
        stub.write_text('#!/bin/sh\nexit 0\n')
        stub.chmod(0o755)
    config = tmp_path / 'config'
    subprocess.run(['bash', str(root / 'scripts/install-autostart.sh')], check=True,
                   env={**os.environ, 'PATH': f'{bin_dir}:' + os.environ['PATH'],
                        'XDG_CONFIG_HOME': str(config)}, capture_output=True)
    unit = config / 'systemd/user/kainos-echo-gateway.service'
    result = subprocess.run(['systemd-analyze', 'verify', str(unit)], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    escaped = str(root).replace('%', '%%')
    assert f'WorkingDirectory={escaped}\n' in unit.read_text()
    assert 'Restart=on-failure' in unit.read_text()
