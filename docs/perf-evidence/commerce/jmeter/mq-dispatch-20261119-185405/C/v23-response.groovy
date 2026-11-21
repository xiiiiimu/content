import groovy.json.JsonSlurper
try {
  def body=new JsonSlurper().parseText(prev.getResponseDataAsString())
  if(body.reservationId) vars.put('reservationId',body.reservationId.toString())
  if(body.code instanceof String && body.code ==~ /[A-Z][A-Z0-9_]{0,79}/) vars.put('businessCode',body.code)
} catch(Exception ignored) { }
// Warmup exercises each worker's real HTTP client. Formal phase contains POST only.
if(vars.get('phaseLabel')=='WARMUP-purchase' && prev.getResponseCode()!='200') {
  log.error('Warmup purchase failed, HTTP '+prev.getResponseCode())
  ctx.getEngine().stopTest(true)
}
if(vars.get('phaseLabel')=='WARMUP-purchase' && prev.getResponseCode()=='200') {
  long deadline=System.nanoTime()+60_000_000_000L
  boolean done=false
  int calls=0
  while(!done && System.nanoTime()<deadline) {
    def connection=new URL(props.getProperty('apiBase','http://127.0.0.1:28080')+'/api/purchase-requests/'+vars.get('reservationId')).openConnection()
    connection.setRequestProperty('Authorization','Bearer '+vars.get('token'))
    connection.connectTimeout=3000;connection.readTimeout=3000
    try {
      calls++
      def result=new JsonSlurper().parse(connection.inputStream)
      done=result.state=='SUCCEEDED'
      if(result.state=='REJECTED') throw new IllegalStateException('Warmup rejected')
    } finally {connection.disconnect()}
    if(!done) Thread.sleep(1000L)
  }
  vars.put('warmPollCalls',Integer.toString(calls))
  if(!done) {ctx.getEngine().stopTest(true);throw new IllegalStateException('Warmup failed to drain in 60s')}
}
