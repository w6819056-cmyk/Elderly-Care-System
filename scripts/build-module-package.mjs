// Usage: node scripts/build-module-package.mjs payload manifest-template.json private.pem output.caremod
// This signs a release; server-side verification remains authoritative.
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
const [payloadArg,manifestArg,keyArg,outputArg]=process.argv.slice(2)
if(!outputArg){process.stderr.write('Usage: node scripts/build-module-package.mjs payload manifest-template.json private.pem output.caremod\n');process.exit(1)}
const root=path.resolve(payloadArg),output=path.resolve(outputArg)
if(fs.existsSync(output))throw new Error('Output already exists; immutable release packages cannot be overwritten')
if(output===root||output.startsWith(root+path.sep))throw new Error('Output must be outside the payload directory')
const payload=new Map(),folded=new Set()
function walk(directory){for(const name of fs.readdirSync(directory).sort()){
 const absolute=path.join(directory,name),stat=fs.lstatSync(absolute)
 if(stat.isSymbolicLink())throw new Error('Symlinks are not permitted')
 if(stat.isDirectory()){walk(absolute);continue}
 if(!stat.isFile())throw new Error('Only regular files are permitted')
 const relative=path.relative(root,absolute).split(path.sep).join('/')
 if(!/^(backend|frontend|migrations)\/[A-Za-z0-9_./-]+$/.test(relative)||relative.split('/').some(p=>p==='.'||p==='..'||p.endsWith('.')||/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$/i.test(p)))throw new Error('Invalid payload path: '+relative)
 if(folded.has(relative.toLowerCase()))throw new Error('Case-insensitive path collision')
 folded.add(relative.toLowerCase())
 if(stat.size>32*1024*1024)throw new Error('Payload file exceeds 32MiB')
 payload.set(relative,fs.readFileSync(absolute))
}}
walk(root)
if(!payload.has('backend/module.jar'))throw new Error('Missing backend/module.jar')
const manifest=JSON.parse(fs.readFileSync(manifestArg,'utf8'))
manifest.files=Object.fromEntries([...payload].map(([name,bytes])=>[name,crypto.createHash('sha256').update(bytes).digest('hex')]))
const metadata=Buffer.from(JSON.stringify(manifest,null,2)+'\n')
if(metadata.length>256*1024)throw new Error('Manifest exceeds 256KiB')
const key=crypto.createPrivateKey(fs.readFileSync(keyArg))
if(key.asymmetricKeyType!=='ed25519')throw new Error('An Ed25519 PKCS8 private key is required')
const signature=Buffer.from(crypto.sign(null,metadata,key).toString('base64'))
const entries=new Map([['manifest.json',metadata],['manifest.sig',signature],...payload])
if(entries.size>256)throw new Error('Too many archive entries')
const crcTable=Array.from({length:256},(_,i)=>{let c=i;for(let j=0;j<8;j++)c=(c&1)?0xedb88320^(c>>>1):c>>>1;return c>>>0})
function crc(bytes){let c=0xffffffff;for(const b of bytes)c=crcTable[(c^b)&255]^(c>>>8);return (c^0xffffffff)>>>0}
const local=[],central=[];let offset=0,total=0
for(const [name,bytes] of entries){
 total+=bytes.length;if(total>64*1024*1024)throw new Error('Expanded package exceeds 64MiB')
 const filename=Buffer.from(name),checksum=crc(bytes),header=Buffer.alloc(30)
 header.writeUInt32LE(0x04034b50);header.writeUInt16LE(20,4);header.writeUInt16LE(0x800,6);header.writeUInt16LE(33,12);header.writeUInt32LE(checksum,14);header.writeUInt32LE(bytes.length,18);header.writeUInt32LE(bytes.length,22);header.writeUInt16LE(filename.length,26)
 local.push(header,filename,bytes)
 const record=Buffer.alloc(46);record.writeUInt32LE(0x02014b50);record.writeUInt16LE(20,4);record.writeUInt16LE(20,6);record.writeUInt16LE(0x800,8);record.writeUInt16LE(33,14);record.writeUInt32LE(checksum,16);record.writeUInt32LE(bytes.length,20);record.writeUInt32LE(bytes.length,24);record.writeUInt16LE(filename.length,28);record.writeUInt32LE(offset,42)
 central.push(record,filename);offset+=header.length+filename.length+bytes.length
}
const directory=Buffer.concat(central),end=Buffer.alloc(22)
end.writeUInt32LE(0x06054b50);end.writeUInt16LE(entries.size,8);end.writeUInt16LE(entries.size,10);end.writeUInt32LE(directory.length,12);end.writeUInt32LE(offset,16)
const archive=Buffer.concat([...local,directory,end])
if(archive.length>64*1024*1024)throw new Error('Archive exceeds 64MiB')
fs.writeFileSync(output,archive,{flag:'wx'})
process.stdout.write(JSON.stringify({output,module:manifest.module?.code,version:manifest.module?.version,sha256:crypto.createHash('sha256').update(archive).digest('hex'),bytes:archive.length})+'\n')
