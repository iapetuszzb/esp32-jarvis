"""Merge the compiled S3 image and create a standalone flashing bundle."""
import argparse,hashlib,json,os,shutil,subprocess,sys,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--out',default='artifacts/release');a=p.parse_args()
root=Path(__file__).resolve().parents[1];dest=root/a.out;dest.mkdir(parents=True,exist_ok=True)
build=root/'firmware/.pio/build/esp32s3-eye'
core=Path(os.environ.get('PLATFORMIO_CORE_DIR',Path.home()/'.platformio'))
tool=core/'packages/tool-esptoolpy/esptool.py'
bootapp=core/'packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin'
merged=dest/'jarvis-esp32s3-merged.bin'
subprocess.run([sys.executable,str(tool),'--chip','esp32s3','merge_bin','-o',str(merged),
 '--flash_mode','keep','--flash_freq','keep','--flash_size','keep',
 '0x0',str(build/'bootloader.bin'),'0x8000',str(build/'partitions.bin'),
 '0xe000',str(bootapp),'0x10000',str(build/'firmware.bin')],check=True)
bundle=dest/'jarvis-esp32s3-firmware.zip'
with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
 z.write(merged,merged.name)
 for name in ['flash_windows.ps1','FLASHING.md']:z.write(root/'firmware'/name,name)
 for name in ['bootloader.bin','partitions.bin','firmware.bin']:z.write(build/name,'parts/'+name)
 z.write(bootapp,'parts/boot_app0.bin')
 z.write(root/'THIRD_PARTY_NOTICES.md','THIRD_PARTY_NOTICES.md')
 for f in (root/'licenses').iterdir():
  if f.is_file():z.write(f,'licenses/'+f.name)
print('Firmware bundle:',bundle)
