import { useEffect, useState } from 'react'
import { getTenantToken } from '../api/session'
export default function StaffAvatar({id,hasAvatar}:{id:string;hasAvatar?:boolean}){
 const [url,setUrl]=useState('')
 useEffect(()=>{if(!hasAvatar)return;const controller=new AbortController();let objectUrl='';const base=import.meta.env.VITE_API_BASE_URL?.replace(/\/$/,'')??'';void fetch(`${base}/api/tenant/v1/hq/staff/${encodeURIComponent(id)}/avatar`,{headers:{Authorization:`Bearer ${getTenantToken()}`},signal:controller.signal}).then(r=>r.ok?r.blob():Promise.reject()).then(blob=>{if(!controller.signal.aborted){objectUrl=URL.createObjectURL(blob);setUrl(objectUrl)}}).catch(()=>{});return()=>{controller.abort();if(objectUrl)URL.revokeObjectURL(objectUrl)}},[id,hasAvatar])
 return <img className="staff-profile-image" src={url||'/images/staff-default-avatar.png'} alt={url?'员工头像':'默认员工头像'}/>
}
