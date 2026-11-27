import { db } from './db.js';

console.log('🌱 [ArchDeck] 초기 데이터 시드(Seed)를 시작합니다...');
db.resetToInitial();
console.log('✅ [ArchDeck] 시드 데이터 초기화가 완료되었습니다.');
console.log(`- 토픽: ${db.getTopics().length}건`);
console.log(`- 비교 매트릭스: ${db.getComparisons().length}건`);
console.log(`- 용어 사전: ${db.getGlossary().length}건`);
console.log(`- 체크리스트: ${db.getChecklists().length}건`);
process.exit(0);
