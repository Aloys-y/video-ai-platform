import boto3, json
from botocore.config import Config

s3 = boto3.client('s3',
    endpoint_url='https://s3.eu-central-003.backblazeb2.com',
    aws_access_key_id='003eeef362c84fa0000000001',
    aws_secret_access_key='K003ATvTFYEBOAo+nkxQqs7moBzbOdg',
    region_name='eu-central-003',
    config=Config(signature_version='s3v4'))

url = s3.generate_presigned_url('get_object',
    Params={'Bucket': 'video-ai-bucket-123', 'Key': 'videos/2026/05/14/upload_74730588_7210dq.mp4'},
    ExpiresIn=7200)

req = {
    'model': 'qwen2.5-vl-7b-instruct',
    'messages': [{
        'role': 'user',
        'content': [
            {'type': 'video_url', 'video_url': {'url': url}},
            {'type': 'text', 'text': '用一句话描述这个视频'}
        ]
    }]
}

json.dump(req, open('/tmp/req.json', 'w'), ensure_ascii=False)
print('OK: /tmp/req.json ready')
