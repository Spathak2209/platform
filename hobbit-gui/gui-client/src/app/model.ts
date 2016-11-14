export class SelectOption {
  constructor(public label: string, public value: string) {}
}

export class ConfigurationParameter {
  constructor(public id: string, public name: string, public datatype: string,
    public description?: string,
    public required?: boolean, public defaultValue?: string,
    public min?: number, public max?: number, public options?: SelectOption[], public range?: string) {}
}

export class ConfigurationParameterValue {
  constructor(public id: string, public name: string, public datatype: string,
    public value: string, public range?: string) {}
}

export class BenchmarkLight {
  constructor(public id: string, public name: string, public description: string) {}
}

export class System {
  constructor(public id: string, public name: string) {}
}

export class Benchmark {
  constructor(public id: string, public name: string, public systems?: System[], public configurationParams?: ConfigurationParameter[]) {}
}

export class ChallengeTask {
  constructor(public id: string, public name: string, public description?: string, public benchmark?: Benchmark,
    public configurationParams?: ConfigurationParameterValue[]) {}
}

export class Challenge {
  constructor(public id: string, public name: string, public description?: string,
   public organizer?: string, public executionDate?: string, public published?: boolean, public closed?: boolean,
   public tasks?: ChallengeTask[]) {}
}

export class UserInfo {
  constructor(public userPrincipalName: string, public preferredUsername: string,
    public name: string, public email: string,
    public roles: string[]) {}
}

export class ChallengeRegistration {
  constructor(public challengeId: string, public taskId: string, public systemId: string) {}
}

export function hasRole(user: UserInfo, role: string): boolean {
    return user.roles.includes(role);
}

export function isChallengeOrganiser(user: UserInfo): boolean {
    return hasRole(user, 'challenge-organiser');
}

export function isSystemProvider(user: UserInfo): boolean {
    return hasRole(user, 'system-provider');
}
